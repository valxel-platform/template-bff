package com.valxel.bff;

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
import org.springframework.http.MediaType;
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
    static final HttpServer agent;
    static final List<Map<String, String>> apiCalls = new CopyOnWriteArrayList<>();
    static final List<Map<String, String>> agentCalls = new CopyOnWriteArrayList<>();
    static final java.util.concurrent.CountDownLatch firstEventSeen = new java.util.concurrent.CountDownLatch(1);
    static volatile boolean streamedBeforeTheEnd;

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
            // The agent: a conversation's stream sends one event, then waits until the test has
            // read it before ending, so a buffering proxy would fail the test.
            agent = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            agent.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            agent.createContext("/", ex -> {
                agentCalls.add(Map.of(
                        "path", ex.getRequestURI().getPath(),
                        "authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                        "cookie", String.valueOf(ex.getRequestHeaders().getFirst("Cookie"))));
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                try (var out = ex.getResponseBody()) {
                    out.write("id: 1\nevent: text\ndata: {\"delta\":\"Hel\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try {
                        streamedBeforeTheEnd = firstEventSeen.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    out.write("id: 2\nevent: idle\ndata: {\"status\":\"idle\"}\n\n".getBytes(StandardCharsets.UTF_8));
                }
            });
            agent.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        keycloak.server.stop(0);
        backend.stop(0);
        agent.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String backendUrl = "http://localhost:" + backend.getAddress().getPort();
        r.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", keycloak::issuer);
        r.add("spring.security.oauth2.client.registration.keycloak.client-secret", () -> "bff-secret");
        // Lists don't merge across property sources, so set the routes' placeholders, not the routes.
        r.add("API_URL", () -> backendUrl);
        r.add("AGENT_URL", () -> "http://localhost:" + agent.getAddress().getPort());
        r.add("WEB_URL", () -> backendUrl);
        r.add("management.server.port", () -> "0");
        r.add("app.bff.mobile.audience", () -> "example-api");
        r.add("app.bff.mobile.min-version", () -> "1.2.0");
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
        Map<String, String> q = UriComponentsBuilder.fromUri(authorize).build().getQueryParams().toSingleValueMap()
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        e -> java.net.URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
        assertThat(authorize.toString()).startsWith(keycloak.issuer() + "/protocol/openid-connect/auth");
        assertThat(q).containsEntry("code_challenge_method", "S256").containsKeys("code_challenge", "state", "nonce");
        keycloak.nonce = q.get("nonce");
        String preLogin = cookie(start, "__Host-SESSION").getValue();

        EntityExchangeResult<byte[]> callback = client().get()
                .uri(b -> b.path("/login/oauth2/code/keycloak").queryParam("code", "c1").queryParam("state", "{state}")
                        .build(q.get("state")))
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
    void theAgentsRepliesStreamThroughAsThePerson() {
        String session = signIn();
        List<String> lines = client().get().uri("/api/agent/conversations/conv-1/stream?after=0")
                .cookie("__Host-SESSION", session).accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseBody()
                .doOnNext(line -> {
                    if (line.contains("Hel")) {
                        firstEventSeen.countDown();
                    }
                })
                .collectList().block(java.time.Duration.ofSeconds(15));
        assertThat(lines).anyMatch(l -> l.contains("Hel")).anyMatch(l -> l.contains("idle"));
        assertThat(streamedBeforeTheEnd).as("the first event arrived before the stream ended").isTrue();
        Map<String, String> call = agentCalls.getLast();
        assertThat(call.get("path")).isEqualTo("/conversations/conv-1/stream"); // /api/agent stripped
        assertThat(call.get("authorization")).isEqualTo("Bearer access-1");
        assertThat(call.get("cookie")).isEqualTo("null");
        client().get().uri("/api/agent/conversations").exchange().expectStatus().isUnauthorized();
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
        String out = new String(client().post().uri("/auth/logout")
                .cookie("__Host-SESSION", session).cookie("XSRF-TOKEN", csrf).header("X-XSRF-TOKEN", csrf)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(out).startsWith("{\"redirect\":\"" + keycloak.issuer() + "/protocol/openid-connect/logout")
                .contains("id_token_hint=").contains("client_id=template-bff").contains("post_logout_redirect_uri=");
        client().get().uri("/api/v1/greetings").cookie("__Host-SESSION", session).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void mobileAppsSendTheirOwnTokenWhichGoesOnToTheApi() throws Exception {
        String token = keycloak.accessToken("example-api", 300);
        EntityExchangeResult<byte[]> res = client().get().uri("/mobile/api/v1/greetings?name=Ada")
                .header("Authorization", "Bearer " + token).header("X-App-Version", "1.2.0")
                .cookie("__Host-SESSION", "x").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.message").isEqualTo("Hello").returnResult();
        Map<String, String> call = apiCalls.stream().filter(c -> c.get("path").equals("/api/v1/greetings")
                && c.get("authorization").equals("Bearer " + token)).findFirst().orElseThrow();
        assertThat(call.get("cookie")).isEqualTo("null");
        assertThat(res.getResponseCookies()).doesNotContainKey("__Host-SESSION"); // no session for apps

        // No cookies, so no CSRF token: a bearer token can't be sent by another site.
        client().put().uri("/mobile/api/v1/greetings/ada")
                .header("Authorization", "Bearer " + token).header("X-App-Version", "1.10.0")
                .exchange().expectStatus().isOk();
        assertThat(apiCalls).anyMatch(c -> c.get("path").equals("/api/v1/greetings/ada") && c.get("method").equals("PUT"));
        // A write that carries cookies is what CSRF attacks look like: it needs the CSRF token.
        client().put().uri("/mobile/api/v1/greetings/ada")
                .header("Authorization", "Bearer " + token).header("X-App-Version", "1.10.0")
                .cookie("__Host-SESSION", "x").exchange().expectStatus().isForbidden();
    }

    @Test
    void mobileTokensMustBeCurrentAndForTheProductsApi() throws Exception {
        client().get().uri("/mobile/api/v1/greetings").header("X-App-Version", "1.2.0")
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/mobile/api/v1/greetings").header("X-App-Version", "1.2.0")
                .header("Authorization", "Bearer " + keycloak.accessToken("another-api", 300))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/mobile/api/v1/greetings").header("X-App-Version", "1.2.0")
                .header("Authorization", "Bearer " + keycloak.accessToken("example-api", -120))
                .exchange().expectStatus().isUnauthorized();
        // A session cookie is no way in either.
        String session = signIn();
        client().get().uri("/mobile/api/v1/greetings").header("X-App-Version", "1.2.0")
                .cookie("__Host-SESSION", session).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void oldAppVersionsAreAskedToUpdate() throws Exception {
        String token = keycloak.accessToken("example-api", 300);
        for (String version : new String[] {"1.1.9", "", "latest"}) {
            client().get().uri("/mobile/api/v1/greetings").header("Authorization", "Bearer " + token)
                    .header("X-App-Version", version).exchange()
                    .expectStatus().isEqualTo(426)
                    .expectHeader().contentType("application/problem+json")
                    .expectBody().jsonPath("$.title").isEqualTo("Upgrade Required");
        }
    }

    @Test
    void theAppsPostTheirFlagCountsOnceAMinuteSignedIn() throws Exception {
        String counts = "{\"flags\":{\"emr.emr-feat-3.kiosk\":{\"on\":{\"requests\":3,\"errors\":1}}}}";
        String session = signIn();
        EntityExchangeResult<byte[]> me = client().get().uri("/auth/me").cookie("__Host-SESSION", session).exchange()
                .expectBody().returnResult();
        String csrf = cookie(me, "XSRF-TOKEN").getValue();
        client().post().uri("/flag-cohorts").contentType(MediaType.APPLICATION_JSON).bodyValue(counts)
                .cookie("__Host-SESSION", session).cookie("XSRF-TOKEN", csrf).header("X-XSRF-TOKEN", csrf)
                .exchange().expectStatus().isAccepted();
        client().post().uri("/flag-cohorts").contentType(MediaType.APPLICATION_JSON).bodyValue(counts)
                .cookie("__Host-SESSION", session).cookie("XSRF-TOKEN", csrf).header("X-XSRF-TOKEN", csrf)
                .exchange().expectStatus().isEqualTo(429); // once a minute
        client().post().uri("/flag-cohorts").contentType(MediaType.APPLICATION_JSON).bodyValue(counts)
                .cookie("__Host-SESSION", session).exchange().expectStatus().isForbidden(); // no CSRF token
        client().post().uri("/flag-cohorts").contentType(MediaType.APPLICATION_JSON).bodyValue(counts)
                .exchange().expectStatus().value(s -> assertThat(s).isIn(401, 403)); // signed out

        String token = keycloak.accessToken("example-api", 300);
        client().post().uri("/mobile/flag-cohorts").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"flags\":{\"emr.emr-feat-3.kiosk\":{\"off\":{\"requests\":500,\"errors\":0}}}}")
                .header("Authorization", "Bearer " + token).header("X-App-Version", "1.2.0")
                .exchange().expectStatus().isBadRequest(); // more than one person's minute
        client().post().uri("/mobile/flag-cohorts").contentType(MediaType.APPLICATION_JSON).bodyValue(counts)
                .header("Authorization", "Bearer " + token).header("X-App-Version", "1.2.0")
                .exchange().expectStatus().isAccepted();
        client().post().uri("/mobile/flag-cohorts").contentType(MediaType.APPLICATION_JSON).bodyValue(counts)
                .header("X-App-Version", "1.2.0").exchange().expectStatus().isUnauthorized();
        assertThat(apiCalls).noneMatch(c -> c.get("path").contains("flag-cohorts")); // counted here, not forwarded
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
