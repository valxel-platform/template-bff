package com.valxel.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignInAuditTest {

    private final List<SignInAudit.Record> sent = new ArrayList<>();

    private SignInAudit audit() {
        SignInAudit.Settings settings = new SignInAudit.Settings("log", null, null, "emr", "staging", "phi", null);
        return new SignInAudit(sent::add, settings, "emr-bff",
                Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC), () -> {});
    }

    @Test
    void signInsFailuresAndSignOutsAreRecordedByIdNeverByName() {
        SignInAudit audit = audit();
        audit.signedIn("u-123", "keycloak");
        audit.signInFailed("access_denied");
        audit.signedOut("u-123", "keycloak");
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0).key()).isEqualTo("session/u-123");
        assertThat(sent.get(0).json())
                .contains("\"type\":\"valxel.audit.v1\"", "\"action\":\"session.sign_in\"", "\"outcome\":\"success\"",
                        "\"id\":\"u-123\"", "\"data_class\":\"phi\"", "\"source\":\"emr/emr-bff\"");
        assertThat(sent.get(1).json()).contains("\"outcome\":\"failure\"", "\"reason\":\"access_denied\"");
        assertThat(sent.get(2).json()).contains("\"action\":\"session.sign_out\"");
    }

    @Test
    void anUnavailableAuditTrailNeverBlocksSigningIn() {
        SignInAudit broken = new SignInAudit(r -> {
            throw new IllegalStateException("kafka down");
        }, new SignInAudit.Settings(null, null, null, null, null, null, null), "bff", Clock.systemUTC(), () -> {});
        broken.signedIn("u-1", "keycloak"); // no exception
    }

    @Test
    void phiAndPaymentsNeverTalkToKafkaInPlainText() {
        assertThatThrownBy(() -> SignInAudit.create(
                        new SignInAudit.Settings("kafka", "127.0.0.1:9", null, null, null, "phi", null), "bff"))
                .hasMessageContaining("must use TLS");
        SignInAudit.requireEncryption(Map.of("security.protocol", "SASL_SSL"), "payments");
    }

    @Test
    void jsonIsEscaped() {
        assertThat(SignInAudit.json(Map.of("a", "q\"\\\n\u0001"))).isEqualTo("{\"a\":\"q\\\"\\\\\\n\\u0001\"}");
        assertThat(SignInAudit.json(List.of("x", true))).isEqualTo("[\"x\",\"true\"]");
    }

    @Test
    void aDeployedBffUsesTlsToRedis() {
        assertThatThrownBy(() -> DeployedRedis.check("redis://redis:6379", "production"))
                .hasMessageContaining("rediss://");
        DeployedRedis.check("rediss://:secret@redis:6380", "production");
        DeployedRedis.check("redis://localhost:6379", "local");
        DeployedRedis.check("redis://localhost:6379", null);
    }
}
