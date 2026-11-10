package com.vrixel.bff;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * People's sessions, and in them their access and refresh tokens, live in Redis. Outside local
 * work the connection must be TLS ({@code rediss://}), with the password in the URL from a
 * secret: a deployed BFF refuses to start on {@code redis://}.
 */
@Configuration(proxyBeanMethods = false)
public class DeployedRedis {

    static final Set<String> LOCAL = Set.of("", "local", "test", "ci");

    public DeployedRedis(@Value("${spring.data.redis.url:}") String url, @Value("${ENVIRONMENT:local}") String environment) {
        check(url, environment);
    }

    static void check(String url, String environment) {
        String env = environment == null ? "" : environment.strip().toLowerCase(Locale.ROOT);
        if (!LOCAL.contains(env) && (url == null || !url.startsWith("rediss://"))) {
            throw new IllegalStateException("REDIS_URL must use TLS (rediss://) in " + env
                    + ": the sessions hold people's tokens");
        }
    }
}
