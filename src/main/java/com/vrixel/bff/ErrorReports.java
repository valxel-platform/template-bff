package com.vrixel.bff;

import io.micrometer.tracing.Tracer;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import java.util.function.Supplier;

/**
 * What an error report to Sentry may carry (Sentry runs only when SENTRY_DSN is set). Sentry groups
 * this component's errors into issues, and its issue alerts reach the SDLC tool as incident signals.
 *
 * <p>An event keeps the error's type, its stack, the endpoint's path and method, the log message's
 * pattern, and the trace id, which ties it to the request's trace and logs in OpenChoreo and to the
 * errors the apps report. Never exception messages, formatted log messages or their arguments,
 * query strings, headers, cookies, bodies, users or breadcrumbs: any of them can carry PHI or a token.
 */
public class ErrorReports implements SentryOptions.BeforeSendCallback {

    private final Supplier<Tracer> tracer;

    public ErrorReports(Supplier<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        event.setUser(null);
        event.setExtras(null);
        event.setBreadcrumbs(null);
        Request request = event.getRequest();
        if (request != null) {
            Request kept = new Request();
            kept.setMethod(request.getMethod());
            kept.setUrl(pathOnly(request.getUrl()));
            event.setRequest(kept);
        }
        if (event.getExceptions() != null) {
            for (SentryException ex : event.getExceptions()) {
                ex.setValue(null);
            }
        }
        Message message = event.getMessage();
        if (message != null) {
            message.setFormatted(null);
            message.setParams(null);
        }
        Tracer t = tracer.get();
        var span = t == null ? null : t.currentSpan();
        if (span != null && !span.context().traceId().isBlank()) {
            event.setTag("trace_id", span.context().traceId());
        }
        return event;
    }

    static String pathOnly(String url) {
        if (url == null) {
            return null;
        }
        int cut = url.indexOf('?');
        int hash = url.indexOf('#');
        if (hash >= 0 && (cut < 0 || hash < cut)) {
            cut = hash;
        }
        return cut < 0 ? url : url.substring(0, cut);
    }
}
