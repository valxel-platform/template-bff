package com.valxel.bff;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Refuses a state-changing request whose Origin isn't the web app's own (defense in depth next to
 * SameSite cookies and the CSRF token). With no origins configured, the request's own origin is
 * the only one allowed.
 */
public class OriginCheck implements WebFilter {

    private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final List<String> allowed;

    public OriginCheck(List<String> allowed) {
        this.allowed = allowed;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (SAFE.contains(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (origin == null || allowed(origin, exchange)) {
            // No Origin: not a browser's cross-site request (browsers send Origin on every
            // cross-origin write); the CSRF token still applies.
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }

    private boolean allowed(String origin, ServerWebExchange exchange) {
        if (!allowed.isEmpty()) {
            return allowed.contains(origin);
        }
        var uri = exchange.getRequest().getURI();
        String self = uri.getScheme() + "://" + uri.getAuthority();
        return origin.equals(self);
    }
}
