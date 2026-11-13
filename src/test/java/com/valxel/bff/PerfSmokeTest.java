package com.valxel.bff;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

/**
 * Performance smoke test (perf.yml): the BFF's own cost on its busiest path, the mobile route
 * (token check, rate limiter in Redis, relay to the API), against a backend that answers at once.
 * 16 requests in flight for a while after a warm-up; writes the 95th and 99th percentile, error
 * rate and requests per second as JSON for perf/compare.py. Runs only with -Dperf.out=<file>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfSystemProperty(named = "perf.out", matches = ".+") // not part of the unit run: no containers start
class PerfSmokeTest {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final FakeKeycloak keycloak;
    static final HttpServer backend;

    static {
        try {
            keycloak = new FakeKeycloak();
            backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            backend.createContext("/", ex -> FakeKeycloak.json(ex, "{\"message\":\"Hello\"}"));
            backend.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(32));
            backend.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        keycloak.server.stop(0);
        backend.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String backendUrl = "http://localhost:" + backend.getAddress().getPort();
        r.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", keycloak::issuer);
        r.add("spring.security.oauth2.client.registration.keycloak.client-secret", () -> "bff-secret");
        r.add("API_URL", () -> backendUrl);
        r.add("WEB_URL", () -> backendUrl);
        r.add("management.server.port", () -> "0");
        r.add("app.bff.mobile.audience", () -> "example-api");
        // One test identity sends everything: lift its rate limit so the limiter is measured, not hit.
        r.add("BFF_RATE_PER_SECOND", () -> "100000");
        r.add("BFF_RATE_BURST", () -> "200000");
    }

    @LocalServerPort
    int port;

    @Test
    void theMobileRouteUnderLoad() throws Exception {
        String token = keycloak.accessToken("example-api", 3600);
        WebClient client = WebClient.create("http://localhost:" + port);
        run(client, token, 500, Collections.synchronizedList(new ArrayList<>()), new AtomicInteger()); // warm-up
        List<Double> times = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        int requests = Integer.getInteger("perf.requests", 3000);
        long start = System.nanoTime();
        run(client, token, requests, times, errors);
        double seconds = (System.nanoTime() - start) / 1e9;
        List<Double> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        Files.writeString(Path.of(System.getProperty("perf.out")), String.format(Locale.ROOT,
                "{\"p95_ms\": %.2f, \"p99_ms\": %.2f, \"error_rate\": %.4f, \"rps\": %.1f}%n",
                at(sorted, 0.95), at(sorted, 0.99), (double) errors.get() / requests, requests / seconds));
    }

    private static void run(WebClient client, String token, int n, List<Double> times, AtomicInteger errors) {
        Flux.range(0, n).flatMap(i -> {
            long t = System.nanoTime();
            return client.get().uri("/mobile/api/v1/greetings?name=perf")
                    .header("Authorization", "Bearer " + token).header("X-App-Version", "9.9.9")
                    .exchangeToMono(res -> res.releaseBody().thenReturn(res.statusCode().is2xxSuccessful()))
                    .onErrorReturn(false)
                    .doOnNext(ok -> {
                        times.add((System.nanoTime() - t) / 1e6);
                        if (!ok) {
                            errors.incrementAndGet();
                        }
                    });
        }, 16).then().block(Duration.ofMinutes(5));
    }

    private static double at(List<Double> sorted, double q) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1));
    }
}
