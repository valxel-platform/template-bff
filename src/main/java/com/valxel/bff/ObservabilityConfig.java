package com.valxel.bff;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Trace ids on responses, and what error reports to Sentry may carry. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @Bean
    TraceResponse traceResponse() {
        return new TraceResponse();
    }

    /** Picked up by Sentry's Spring Boot integration whenever SENTRY_DSN is set. */
    @Bean
    ErrorReports errorReports(ObjectProvider<Tracer> tracer) {
        return new ErrorReports(tracer::getIfAvailable);
    }
}
