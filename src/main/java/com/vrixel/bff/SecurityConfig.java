package com.vrixel.bff;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Who may do what at the edge (see the package documentation).
 *
 * <ul>
 *   <li>{@code /api/**}: signed-in people only; otherwise 401 (never a redirect, so the web app
 *       can show its sign-in button).
 *   <li>{@code /auth/login} starts sign-in, {@code /auth/logout} (POST) ends it here and answers
 *       where to end it at Keycloak too,
 *       {@code /auth/me} says who is signed in.
 *   <li>Everything else is the web app's pages, open: they hold no data.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain edge(
            ServerHttpSecurity http, ReactiveClientRegistrationRepository clients, BffProperties props) {
        // PKCE as well as the client's own credentials: a stolen authorization code is useless.
        DefaultServerOAuth2AuthorizationRequestResolver resolver = new DefaultServerOAuth2AuthorizationRequestResolver(clients);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        JsonLogout logout = new JsonLogout(clients, props.afterLogout());

        // The web app reads XSRF-TOKEN and sends it back as X-XSRF-TOKEN on every write. Plain
        // (not BREACH-masked) tokens: the cookie is SameSite=Strict and the value is not reflected.
        CookieServerCsrfTokenRepository csrf = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(c -> c.secure(true).sameSite("Strict").path("/"));

        return http
                .authorizeExchange(auth -> auth
                        .pathMatchers("/api/**").authenticated()
                        .pathMatchers("/auth/me", "/auth/login", "/auth/logout").permitAll()
                        .anyExchange().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2Login(login -> login
                        .authorizationRequestResolver(resolver)
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler(props.afterLogin()))
                        .authenticationFailureHandler(SecurityConfig::signInFailed))
                .oauth2Client(c -> {})
                .logout(l -> l
                        .requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/auth/logout"))
                        .logoutSuccessHandler(logout))
                .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
                .addFilterAt(new OriginCheck(props.origins()), SecurityWebFiltersOrder.CSRF)
                .headers(h -> h
                        .hsts(s -> s.includeSubdomains(true).maxAge(java.time.Duration.ofDays(365)))
                        .frameOptions(f -> f.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.SAME_ORIGIN)))
                .build();
    }

    /** Sign-in failed (a stale or forged callback, Keycloak refused): say why in the log, never the code. */
    static reactor.core.publisher.Mono<Void> signInFailed(
            org.springframework.security.web.server.WebFilterExchange exchange,
            org.springframework.security.core.AuthenticationException e) {
        String code = e instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException o
                ? o.getError().getErrorCode() + " " + o.getError().getDescription() : e.getClass().getSimpleName();
        LOG.warn("sign-in failed: {}", code);
        return new org.springframework.security.web.server.DefaultServerRedirectStrategy()
                .sendRedirect(exchange.getExchange(), java.net.URI.create("/?signin=failed"));
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * People's tokens live in their session, in Redis, so every replica can relay and refresh them;
     * nothing about a person is kept in a replica's memory.
     */
    @Bean
    ServerOAuth2AuthorizedClientRepository authorizedClients() {
        return new WebSessionServerOAuth2AuthorizedClientRepository();
    }
}
