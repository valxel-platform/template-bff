package com.vrixel.bff;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * After the session here is gone, tell the web app where to send the browser to end the Keycloak
 * session too (RP-initiated logout): {@code 200 {"redirect": "<end_session_endpoint>?id_token_hint=..."}}.
 * A JSON answer instead of a redirect, so the app's fetch can follow it with a top-level navigation
 * (a cross-origin redirect after a form post would be blocked by the page's form-action policy).
 */
public class JsonLogout implements ServerLogoutSuccessHandler {

    private final ReactiveClientRegistrationRepository clients;
    private final String afterLogout;

    public JsonLogout(ReactiveClientRegistrationRepository clients, String afterLogout) {
        this.clients = clients;
        this.afterLogout = afterLogout;
    }

    @Override
    public Mono<Void> onLogoutSuccess(WebFilterExchange exchange, Authentication authentication) {
        ServerHttpRequest request = exchange.getExchange().getRequest();
        String back = afterLogout.replace("{baseUrl}", request.getURI().getScheme() + "://" + request.getURI().getAuthority());
        return clients.findByRegistrationId("keycloak")
                .map(reg -> endSession(reg, authentication, back))
                .defaultIfEmpty(back)
                .flatMap(url -> write(exchange.getExchange().getResponse(), url));
    }

    static String endSession(ClientRegistration reg, Authentication authentication, String back) {
        Map<String, Object> meta = reg.getProviderDetails().getConfigurationMetadata();
        Object endpoint = meta.get("end_session_endpoint");
        if (endpoint == null) {
            return back;
        }
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(endpoint.toString())
                .queryParam("client_id", reg.getClientId())
                .queryParam("post_logout_redirect_uri", back);
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            url.queryParam("id_token_hint", user.getIdToken().getTokenValue());
        }
        return url.encode().build().toUriString();
    }

    private static Mono<Void> write(ServerHttpResponse response, String url) {
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        String json = "{\"redirect\":\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8))));
    }
}
