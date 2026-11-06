package com.vrixel.bff;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Turns away mobile app versions older than the oldest still supported, with 426 Upgrade Required:
 * the app then asks the person to update from the store. Store builds stay installed for months, so
 * this is how the product stops supporting an API shape that old versions still use.
 *
 * <p>The app sends its version as {@code X-App-Version} (e.g. 1.4.0). A request without one, or
 * with one that isn't a version, is treated as too old.
 */
public class MinimumVersion implements WebFilter {

    static final String HEADER = "X-App-Version";

    private static final byte[] PROBLEM = """
            {"type":"about:blank","title":"Upgrade Required","status":426,\
            "detail":"This version of the app is no longer supported. Update it from the store."}"""
            .getBytes(StandardCharsets.UTF_8);

    private final int[] minimum;

    /** @param minimum e.g. 1.4.0; empty to accept any version */
    public MinimumVersion(String minimum) {
        this.minimum = minimum == null || minimum.isBlank() ? null : parse(minimum);
        if (minimum != null && !minimum.isBlank() && this.minimum == null) {
            throw new IllegalArgumentException("app.bff.mobile.min-version is not a version: " + minimum);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (minimum == null || supported(exchange.getRequest().getHeaders().getFirst(HEADER))) {
            return chain.filter(exchange);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UPGRADE_REQUIRED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(PROBLEM)));
    }

    boolean supported(String version) {
        int[] v = version == null ? null : parse(version);
        if (v == null) {
            return false;
        }
        for (int i = 0; i < Math.max(v.length, minimum.length); i++) {
            int a = i < v.length ? v[i] : 0;
            int b = i < minimum.length ? minimum[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return true;
    }

    /** 1.4.0 → [1, 4, 0]; a pre-release or build suffix (1.4.0-beta.2, 1.4.0+42) is ignored. */
    static int[] parse(String version) {
        String core = version.strip().split("[-+]", 2)[0];
        if (!core.matches("\\d{1,6}(\\.\\d{1,6}){0,3}")) {
            return null;
        }
        String[] parts = core.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = Integer.parseInt(parts[i]);
        }
        return numbers;
    }
}
