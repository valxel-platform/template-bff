package com.vrixel.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class TraceResponseTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    static MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request, Span span, String downstream) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        if (span != null) {
            var ctx = new ServerRequestObservationContext(exchange.getRequest(), exchange.getResponse(), exchange.getAttributes());
            var tracing = new TracingObservationHandler.TracingContext();
            tracing.setSpan(span);
            ctx.put(TracingObservationHandler.TracingContext.class, tracing);
            exchange.getAttributes().put(ServerRequestObservationContext.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, ctx);
        }
        new TraceResponse().filter(exchange, ex -> {
            if (downstream != null) {
                ex.getResponse().getHeaders().set("traceresponse", downstream);
            }
            return Mono.empty();
        }).block();
        exchange.getResponse().setComplete().block();
        return exchange;
    }

    static Span span() {
        Span span = mock(Span.class);
        TraceContext ctx = mock(TraceContext.class);
        when(span.context()).thenReturn(ctx);
        when(ctx.traceId()).thenReturn(TRACE);
        when(ctx.spanId()).thenReturn("00f067aa0ba902b7");
        return span;
    }

    @Test
    void theResponseNamesThisRequestsSpan() {
        var ex = run(MockServerHttpRequest.get("/api/x"), span(), null);
        assertThat(ex.getResponse().getHeaders().getFirst("traceresponse")).isEqualTo("00-" + TRACE + "-00f067aa0ba902b7-01");
    }

    @Test
    void aServicesOwnTraceResponsePassesThrough() {
        String service = "00-" + TRACE + "-b7ad6b7169203331-01";
        var ex = run(MockServerHttpRequest.get("/api/x"), span(), service);
        assertThat(ex.getResponse().getHeaders().getFirst("traceresponse")).isEqualTo(service);
    }

    @Test
    void withoutASpanTheCallersTraceIsUsedAndJunkIsIgnored() {
        var ex = run(MockServerHttpRequest.get("/api/x").header("traceparent", "00-" + TRACE + "-1111111111111111-01"), null, null);
        assertThat(ex.getResponse().getHeaders().getFirst("traceresponse")).isEqualTo("00-" + TRACE + "-1111111111111111-01");
        var junk = run(MockServerHttpRequest.get("/api/x").header("traceparent", "nonsense"), null, null);
        assertThat(junk.getResponse().getHeaders().getFirst("traceresponse")).isNull();
    }
}
