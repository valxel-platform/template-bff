package com.vrixel.bff;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** What the web app needs to know about the session; never a token. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** Who is signed in. Also issues the CSRF cookie the web app sends back on writes. */
    @GetMapping("/me")
    public Mono<Map<String, Object>> me(@AuthenticationPrincipal OidcUser user, ServerWebExchange exchange) {
        Mono<CsrfToken> csrf = exchange.getAttributeOrDefault(CsrfToken.class.getName(), Mono.empty());
        return csrf.map(t -> t.getToken()).defaultIfEmpty("").map(ignored -> {
            Map<String, Object> me = new LinkedHashMap<>();
            me.put("authenticated", user != null);
            if (user != null) {
                me.put("sub", user.getSubject());
                me.put("name", user.getFullName() != null ? user.getFullName() : user.getPreferredUsername());
            }
            return me;
        });
    }

    /** Start sign-in. */
    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/oauth2/authorization/keycloak")).build();
    }
}
