package com.valxel.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorReportsTest {

    @Test
    void anErrorReportKeepsTypePathAndTraceAndNoTokensOrPeople() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext ctx = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(ctx);
        when(ctx.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");

        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setMethod("GET");
        request.setUrl("https://bff/mobile/api/x?code=abc");
        request.setCookies("SESSION=s");
        request.setHeaders(Map.of("Authorization", "Bearer t"));
        event.setRequest(request);
        SentryException ex = new SentryException();
        ex.setType("WebClientRequestException");
        ex.setValue("Connection refused: records-service/10.0.0.4:8080");
        event.setExceptions(List.of(ex));

        SentryEvent out = new ErrorReports(() -> tracer).execute(event, new Hint());
        assertThat(out.getRequest().getUrl()).isEqualTo("https://bff/mobile/api/x");
        assertThat(out.getRequest().getCookies()).isNull();
        assertThat(out.getRequest().getHeaders()).isNull();
        assertThat(out.getExceptions().get(0).getValue()).isNull();
        assertThat(out.getTag("trace_id")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(ErrorReports.pathOnly("/a#b")).isEqualTo("/a");
        assertThat(new ErrorReports(() -> null).execute(new SentryEvent(), new Hint()).getTag("trace_id")).isNull();
    }
}
