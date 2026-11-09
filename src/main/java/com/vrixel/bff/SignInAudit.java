package com.vrixel.bff;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-ins, failed sign-ins and sign-outs, on the product's audit trail (CloudEvents
 * {@code vrixel.audit.v1}, the same records the services publish), so login monitoring doesn't
 * depend on logs. Records name the person ({@code sub}) and the client, never tokens, codes or
 * names. They are sent without waiting: an unavailable audit trail never blocks signing in or out
 * (a dropped record is logged as an error).
 */
public class SignInAudit implements AutoCloseable {

    public static final String EVENT_TYPE = "vrixel.audit.v1";
    static final Set<String> ENCRYPTED_CLASSES = Set.of("phi", "payments");
    private static final Logger LOG = LoggerFactory.getLogger("audit");

    private final Consumer<Record> sink;
    private final Settings settings;
    private final String service;
    private final Clock clock;
    private final AutoCloseable closer;

    /** One record ready to send: its key (the subject) and its JSON. */
    public record Record(String key, String json) {}

    /**
     * app.audit.*
     *
     * @param sink {@code kafka} in deployed environments, {@code log} locally
     * @param kafka extra Kafka client settings (security.protocol, sasl.*); phi and payments
     *     products must use SSL or SASL_SSL
     */
    @ConfigurationProperties("app.audit")
    public record Settings(
            String sink, String bootstrap, String topic, String product, String environment, String dataClass,
            Map<String, String> kafka) {

        public Settings {
            sink = blank(sink, "log");
            bootstrap = blank(bootstrap, "");
            topic = blank(topic, "audit.v1");
            product = blank(product, "");
            environment = blank(environment, "");
            dataClass = blank(dataClass, "general");
            kafka = kafka == null ? Map.of() : Map.copyOf(kafka);
        }

        private static String blank(String v, String fallback) {
            return v == null || v.isBlank() ? fallback : v.strip();
        }
    }

    SignInAudit(Consumer<Record> sink, Settings settings, String service, Clock clock, AutoCloseable closer) {
        this.sink = sink;
        this.settings = settings;
        this.service = service;
        this.clock = clock;
        this.closer = closer;
    }

    public static SignInAudit create(Settings settings, String service) {
        return switch (settings.sink()) {
            case "kafka" -> {
                if (settings.bootstrap().isEmpty()) {
                    throw new IllegalStateException("Set app.audit.bootstrap (AUDIT_KAFKA_BOOTSTRAP) for the kafka audit sink");
                }
                requireEncryption(settings.kafka(), settings.dataClass());
                Map<String, Object> config = new HashMap<>();
                config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrap());
                config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
                config.put(ProducerConfig.ACKS_CONFIG, "all");
                config.put(ProducerConfig.CLIENT_ID_CONFIG, "audit");
                config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
                config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
                config.putAll(settings.kafka());
                Producer<String, byte[]> producer =
                        new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
                yield new SignInAudit(kafka(producer, settings.topic()), settings, service, Clock.systemUTC(), producer::close);
            }
            case "log" -> new SignInAudit(r -> LOG.info(r.json()), settings, service, Clock.systemUTC(), () -> {});
            default -> throw new IllegalStateException("Unknown audit sink " + settings.sink() + ": use kafka or log");
        };
    }

    static Consumer<Record> kafka(Producer<String, byte[]> producer, String topic) {
        return r -> {
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(topic, r.key(), r.json().getBytes(StandardCharsets.UTF_8));
            record.headers().add("ce_type", EVENT_TYPE.getBytes(StandardCharsets.UTF_8));
            producer.send(record, (meta, error) -> {
                if (error != null) {
                    LOG.error("audit record dropped ({}): {}", error.getClass().getSimpleName(), r.key());
                }
            });
        };
    }

    /** PHI and payments products never talk to Kafka in plain text: refuse to start instead. */
    static void requireEncryption(Map<String, String> kafka, String dataClass) {
        String protocol = kafka.getOrDefault("security.protocol", "PLAINTEXT").toUpperCase(Locale.ROOT);
        if (ENCRYPTED_CLASSES.contains(dataClass) && !Set.of("SSL", "SASL_SSL").contains(protocol)) {
            throw new IllegalStateException("The audit Kafka connection of a " + dataClass
                    + " product must use TLS: set security.protocol to SSL or SASL_SSL (it is " + protocol + ")");
        }
    }

    public void signedIn(String person, String client) {
        send("session.sign_in", person, client, "success", "");
    }

    /** {@code why} is the OAuth error code (e.g. access_denied), never the request. */
    public void signInFailed(String why) {
        send("session.sign_in", "anonymous", "", "failure", why);
    }

    public void signedOut(String person, String client) {
        send("session.sign_out", person, client, "success", "");
    }

    void send(String action, String person, String client, String outcome, String reason) {
        try {
            String subject = "session/" + (person == null || person.isBlank() ? "anonymous" : person);
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("id", person == null ? "" : person);
            actor.put("type", "user");
            actor.put("client", client == null ? "" : client);
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", "session");
            resource.put("ids", List.of());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("product", settings.product());
            data.put("service", service);
            data.put("environment", settings.environment());
            data.put("data_class", settings.dataClass());
            data.put("action", action);
            data.put("actor", actor);
            data.put("on_behalf_of", "");
            data.put("resource", resource);
            data.put("outcome", outcome);
            data.put("reason", reason == null ? "" : reason.substring(0, Math.min(reason.length(), 300)));
            data.put("details", Map.of());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("specversion", "1.0");
            event.put("id", UUID.randomUUID().toString());
            event.put("source", settings.product().isBlank() ? service : settings.product() + "/" + service);
            event.put("type", EVENT_TYPE);
            event.put("time", Instant.now(clock).toString());
            event.put("subject", subject);
            event.put("datacontenttype", "application/json");
            event.put("data", data);
            sink.accept(new Record(subject, json(event)));
        } catch (RuntimeException e) {
            LOG.error("audit record dropped ({}): {}", e.getClass().getSimpleName(), action);
        }
    }

    /** A small JSON writer for maps, lists, strings and booleans (no dependency on a Jackson version). */
    static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Map<?, ?> m) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.append(first ? "" : ",").append(json(String.valueOf(e.getKey()))).append(':').append(json(e.getValue()));
                first = false;
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> l) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                out.append(i == 0 ? "" : ",").append(json(l.get(i)));
            }
            return out.append(']').toString();
        }
        return json(String.valueOf(value));
    }

    @Override
    public void close() throws Exception {
        closer.close();
    }
}
