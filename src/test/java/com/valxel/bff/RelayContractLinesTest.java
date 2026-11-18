package com.valxel.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.valxel.bff.FlagCohortRelay.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The summary lines the BFF logs for the web and mobile apps, written to
 * build/contract-lines/lines.txt for CI to check against the shared contracts the SDLC tool reads
 * them by: contracts/events/, byte-identical copies of the platform's
 * (.github/scripts/check_contract_lines.py; the Valxel SDLC keeps them in step, so update them from
 * there, never here).
 */
class RelayContractLinesTest {

    private static final Instant T = Instant.parse("2026-11-06T10:00:00Z");
    private static final String KEY = "acme.acme-feat-1.triage";

    private static Map<String, Object> side(int requests, int errors) {
        return Map.of("requests", requests, "errors", errors, "events", Map.of("referral_triaged", 1));
    }

    @Test
    void theRelayedLinesAreWrittenForTheContractCheck() throws IOException {
        FlagCohortRelay relay = new FlagCohortRelay();
        assertThat(relay.accept("u1", "web", Map.of("flags", Map.of(KEY, Map.of("on", side(3, 1), "off", side(2, 0))),
                "events", Map.of("referral_triaged", 2)), T)).isEqualTo(Result.ACCEPTED);
        assertThat(relay.accept("u2", "mobile", Map.of("flags", Map.of(KEY, Map.of("on", side(1, 0))),
                "events", Map.of("referral_triaged", 1)), T)).isEqualTo(Result.ACCEPTED);

        var lines = relay.drain();
        Path out = Path.of("build", "contract-lines", "lines.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join("\n", lines) + "\n");
        assertThat(lines).anyMatch(l -> l.startsWith(FlagCohortRelay.MARKER + " "))
                .anyMatch(l -> l.startsWith(FlagCohortRelay.EVENTS_MARKER + " "));
    }
}
