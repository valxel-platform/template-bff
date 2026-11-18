package com.valxel.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The sign-in audit events this BFF builds, written to build/audit-events/events.jsonl for CI to
 * check against the shared contract: contracts/audit/valxel.audit.v1.schema.json, a byte-identical
 * copy of ss-audit-sink's schema (.github/scripts/check_audit_events.py; the Valxel SDLC checks
 * daily that the copy is still the sink's). Update the copy from the sink; never edit it here.
 */
class SignInAuditSchemaContractTest {

    @Test
    void theEventsThisBffBuildsAreWrittenForTheSchemaCheck() throws IOException {
        List<SignInAudit.Record> sent = new ArrayList<>();
        SignInAudit.Settings settings = new SignInAudit.Settings("log", null, null, "acme", "test", "phi", null);
        SignInAudit audit = new SignInAudit(sent::add, settings, "acme-bff", Clock.systemUTC(), () -> {});
        audit.signedIn("u-123", "keycloak");
        audit.signInFailed("access_denied");
        audit.signedOut("u-123", "keycloak");

        Path out = Path.of("build", "audit-events", "events.jsonl");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sent.stream().map(SignInAudit.Record::json).collect(Collectors.joining("\n")) + "\n");
        assertThat(sent).hasSize(3);
    }
}
