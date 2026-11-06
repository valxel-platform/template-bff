package com.vrixel.bff;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Every response says which trace it belongs to, in a W3C {@code traceresponse} header
 * ({@code 00-<trace id>-<span id>-01}). The web and mobile apps put it on the errors they report,
 * so the SDLC tool can tell that a failure people saw and an error behind it are one incident.
 *
 * <p>A response from a service behind this BFF already carries the service's own (same trace);
 * otherwise, as when the service can't be reached, it is this request's span here.
 */
public class TraceResponse implements WebFilter {

    static final String HEADER = "traceresponse";
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            if (!headers.containsHeader(HEADER)) {
                String value = value(exchange);
                if (value != null) {
                    headers.set(HEADER, value);
                }
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /** This request's span, or the trace the caller started, or null. */
    static String value(ServerWebExchange exchange) {
        Span span = ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(ctx -> ctx.get(TracingObservationHandler.TracingContext.class))
                .map(tracing -> ((TracingObservationHandler.TracingContext) tracing).getSpan())
                .orElse(null);
        if (span != null && !span.context().traceId().isBlank()) {
            return "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01";
        }
        String parent = exchange.getRequest().getHeaders().getFirst("traceparent");
        if (parent != null) {
            var m = TRACEPARENT.matcher(parent.trim());
            if (m.matches()) {
                return "00-" + m.group(1) + "-" + m.group(2) + "-01";
            }
        }
        return null;
    }
}
