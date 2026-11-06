package com.vrixel.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The BFF end to end: sign-in with PKCE against a (fake) Keycloak realm, sessions in Redis, the
 * person's token relayed to the API and never to the browser, and cross-site writes refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BffIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final FakeKeycloak keycloak;
    static final HttpServer backend;
    static final List<Map<String, String>> apiCalls = new CopyOnWriteArrayList<>();

    static {
        try {
            keycloak = new FakeKeycloak();
            backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            backend.createContext("/", ex -> {
                apiCalls.add(Map.of(
                        "path", ex.getRequestURI().getPath(),
                        "method", ex.getRequestMethod(),
                        "authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                        "cookie", String.valueOf(ex.getRequestHeaders().getFirst("Cookie"))));
                FakeKeycloak.json(ex, ex.getRequestURI().getPath().startsWith("/api") ? "{\"message\":\"Hello\"}" : "{\"page\":\"app\"}");
            });
            backend.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        keycloak.server.stop(0);
        backend.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String backendUrl = "http://localhost:" + backend.getAddress().getPort();
        r.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", keycloak::issuer);
        r.add("spring.security.oauth2.client.registration.keycloak.client-secret", () -> "bff-secret");
        r.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> backendUrl);
        r.add("spring.cloud.gateway.server.webflux.routes[1].uri", () -> backendUrl);
        r.add("management.server.port", () -> "0");
    }

    @LocalServerPort
    int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static ResponseCookie cookie(EntityExchangeResult<?> res, String name) {
        return res.getResponseCookies().getFirst(name);
    }

    /** Signs in and returns the session cookie value. */
    private String signIn() {
        EntityExchangeResult<byte[]> start = client().get().uri("/oauth2/authorization/keycloak").exchange()
                .expectStatus().isFound().expectBody().returnResult();
        URI authorize = start.getResponseHeaders().getLocation();
        Map<String, String> q = UriComponentsBuilder.fromUri(authorize).build().getQueryParams().toSingleValueMap();
        assertThat(authorize.toString()).startsWith(keycloak.issuer() + "/protocol/openid-connect/auth");
        assertThat(q).containsEntry("code_challenge_method", "S256").containsKeys("code_challenge", "state", "nonce");
        keycloak.nonce = q.get("nonce");
        String preLogin = cookie(start, "__Host-SESSION").getValue();

        EntityExchangeResult<byte[]> callback = client().get()
                .uri("/login/oauth2/code/keycloak?code=c1&state=" + q.get("state"))
                .cookie("__Host-SESSION", preLogin).exchange()
                .expectStatus().isFound().expectBody().returnResult();
        assertThat(callback.getResponseHeaders().getLocation().getPath()).isEqualTo("/");
        ResponseCookie session = cookie(callback, "__Host-SESSION");
        assertThat(session.getValue()).isNotEqualTo(preLogin); // a new session id at sign-in
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.isSecure()).isTrue();
        assertThat(session.getSameSite()).isEqualToIgnoringCase("Strict");
        return session.getValue();
    }

    @Test
    void signedOutCallersGet401NotARedirect() {
        client().get().uri("/api/v1/greetings").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void signInUsesPkceAndTheClientsCredentials() {
        signIn();
        Map<String, String> req = keycloak.tokenRequests.getLast();
        assertThat(req).containsEntry("grant_type", "authorization_code").containsEntry("code", "c1").containsKey("code_verifier");
        assertThat(keycloak.tokenAuth.getLast()).startsWith("Basic ");
    }

    @Test
    void thePersonsTokenGoesToTheApiNeverToTheBrowser() {
        String session = signIn();
        client().get().uri("/api/v1/greetings?name=Ada").cookie("__Host-SESSION", session).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.message").isEqualTo("Hello");
        Map<String, String> call = apiCalls.stream().filter(c -> c.get("path").equals("/api/v1/greetings")).reduce((a, b) -> b).orElseThrow();
        assertThat(call.get("authorization")).isEqualTo("Bearer access-1");
        assertThat(call.get("cookie")).isEqualTo("null"); // the session cookie stays at the edge

        String me = new String(client().get().uri("/auth/me").cookie("__Host-SESSION", session).exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(me).contains("\"authenticated\":true").contains("\"sub\":\"user-1\"")
                .doesNotContain("access-1").doesNotContain("refresh-1");
    }

    @Test
    void writesNeedTheCsrfTokenAndTheAppsOrigin() {
        String session = signIn();
        client().post().uri("/api/v1/greetings").cookie("__Host-SESSION", session).exchange()
                .expectStatus().isForbidden();

        EntityExchangeResult<byte[]> me = client().get().uri("/auth/me").cookie("__Host-SESSION", session).exchange()
                .expectBody().returnResult();
        String csrf = cookie(me, "XSRF-TOKEN").getValue();
        client().post().uri("/api/v1/greetings")
                .cookie("__Host-SESSION", session).cookie("XSRF-TOKEN", csrf).header("X-XSRF-TOKEN", csrf)
                .exchange().expectStatus().isOk();
        client().post().uri("/api/v1/greetings").header("Origin", "https://evil.example")
                .cookie("__Host-SESSION", session).cookie("XSRF-TOKEN", csrf).header("X-XSRF-TOKEN", csrf)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void pagesComeFromTheWebAppWithoutCookies() {
        client().get().uri("/patients").cookie("__Host-SESSION", "x").exchange().expectStatus().isOk();
        Map<String, String> call = apiCalls.stream().filter(c -> c.get("path").equals("/patients")).findFirst().orElseThrow();
        assertThat(call.get("cookie")).isEqualTo("null");
        assertThat(call.get("authorization")).isEqualTo("null");
    }

    @Test
    void signOutEndsTheKeycloakSessionToo() {
        String session = signIn();
        EntityExchangeResult<byte[]> me = client().get().uri("/auth/me").cookie("__Host-SESSION", session).exchange()
                .expectBody().returnResult();
        String csrf = cookie(me, "XSRF-TOKEN").getValue();
        EntityExchangeResult<byte[]> out = client().post().uri("/auth/logout")
                .cookie("__Host-SESSION", session).cookie("XSRF-TOKEN", csrf).header("X-XSRF-TOKEN", csrf)
                .exchange().expectStatus().isFound().expectBody().returnResult();
        assertThat(out.getResponseHeaders().getLocation().toString())
                .startsWith(keycloak.issuer() + "/protocol/openid-connect/logout").contains("id_token_hint=");
        client().get().uri("/api/v1/greetings").cookie("__Host-SESSION", session).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void anonymousMeSaysSignedOut() {
        client().get().uri("/auth/me").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.authenticated").isEqualTo(false);
        client().get().uri("/auth/login").exchange().expectStatus().isFound()
                .expectHeader().location("/oauth2/authorization/keycloak");
        assertThat(HttpStatus.FOUND.value()).isEqualTo(302);
        assertThat(apiCalls.stream().map(c -> c.get("path")).collect(Collectors.toSet())).doesNotContain("/auth/me");
    }
}
