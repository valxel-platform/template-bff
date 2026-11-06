package com.vrixel.bff;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The product's mobile apps at the edge: {@code /mobile/api/**}.
 *
 * <p>A phone can't keep a secret, so a mobile app is a public client of the realm with its own
 * tokens (PKCE in the system browser) and sends them as bearer tokens: no session and no cookies,
 * so a token-only request can't be forged by another site; a write that carries cookies still
 * needs the CSRF token. This chain checks each token here (signed by the realm, not expired,
 * issued for the product's API) and the app's version, then the {@code mobile} route strips
 * {@code /mobile} and passes the request, token and all, to the service, which checks the token
 * again and applies its permissions (VX-17). One edge for the web app and the mobile apps.
 */
@Configuration(proxyBeanMethods = false)
public class MobileSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityWebFilterChain mobile(
            ServerHttpSecurity http,
            BffProperties props,
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri:}") String issuer) {
        BffProperties.Mobile mobile = props.mobile();
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/mobile/**"))
                .authorizeExchange(auth -> {
                    if (mobile.enabled()) {
                        auth.anyExchange().authenticated();
                    } else {
                        auth.anyExchange().denyAll(); // the product has no mobile app
                    }
                })
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtDecoder(decoder(issuer, mobile.audience()))))
                // CSRF rides on cookies the browser adds by itself. A bearer token is never added by
                // the browser, so token-only requests need no CSRF token; one that carries cookies
                // still must have one (and the route drops cookies anyway).
                .csrf(c -> c.requireCsrfProtectionMatcher(MobileSecurityConfig::writeWithCookies))
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .addFilterAt(new MinimumVersion(mobile.minVersion()), SecurityWebFiltersOrder.FIRST)
                .headers(h -> h.hsts(s -> s.includeSubdomains(true).maxAge(Duration.ofDays(365))))
                .build();
    }

    /** A state-changing request that carries cookies: the kind CSRF attacks ride on. */
    static Mono<ServerWebExchangeMatcher.MatchResult> writeWithCookies(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        boolean safe = SAFE.contains(request.getMethod());
        return !safe && !request.getCookies().isEmpty()
                ? ServerWebExchangeMatcher.MatchResult.match()
                : ServerWebExchangeMatcher.MatchResult.notMatch();
    }

    private static final java.util.Set<HttpMethod> SAFE =
            java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

    /**
     * Tokens from the product's realm for the product's API. The realm's keys are fetched on first
     * use (and when a token names a new key), so the BFF starts even while Keycloak is down.
     */
    static ReactiveJwtDecoder decoder(String issuer, String audience) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            return token -> Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("mobile tokens are not accepted"));
        }
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build();
        OAuth2TokenValidator<Jwt> forTheApi = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), forTheApi));
        return decoder;
    }
}
