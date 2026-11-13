package com.vrixel.bff;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Where the apps post their per-flag counts and product events each minute ({@link FlagCohortRelay}):
 * {@code POST /flag-cohorts} from the web app (the session and the CSRF token, like any write) and
 * {@code POST /mobile/flag-cohorts} from the mobile apps (their bearer token). 202 when counted,
 * 429 when the same person posted within the minute, 400 when the counts aren't valid.
 */
@RestController
public class FlagCohortController {

    private final FlagCohortRelay relay;

    public FlagCohortController(FlagCohortRelay relay) {
        this.relay = relay;
    }

    @PostMapping("/flag-cohorts")
    public Mono<ResponseEntity<Void>> web(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return count("web", body, exchange);
    }

    @PostMapping("/mobile/flag-cohorts")
    public Mono<ResponseEntity<Void>> mobile(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
        return count("mobile", body, exchange);
    }

    private Mono<ResponseEntity<Void>> count(String source, Map<String, Object> body, ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("")
                .map(user -> ResponseEntity.status(switch (relay.accept(user, source, body, Instant.now())) {
                    case ACCEPTED -> HttpStatus.ACCEPTED;
                    case TOO_SOON -> HttpStatus.TOO_MANY_REQUESTS;
                    case INVALID -> user.isEmpty() ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
                }).build());
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        /** Logs the apps' counts once a minute for the SDLC tool's gradual releases. */
        @Bean(destroyMethod = "close")
        FlagCohortRelay flagCohortRelay(org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
            return FlagCohortRelay.reporting(meters.getIfAvailable());
        }
    }
}
