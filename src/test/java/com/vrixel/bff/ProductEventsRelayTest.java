package com.vrixel.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.vrixel.bff.FlagCohortRelay.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The apps' product events: per flag and side for gradual releases, and in total for the staging check. */
class ProductEventsRelayTest {

    private static final String KEY = "emr.emr-feat-3.check-in-kiosk";
    private static final Instant T = Instant.parse("2026-11-11T10:00:00Z");

    private static Map<String, Object> side(int requests, Map<String, Object> events) {
        return Map.of("requests", requests, "errors", 0, "events", events);
    }

    @Test
    void eventsCountPerFlagSideAndInTotal() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FlagCohortRelay relay = new FlagCohortRelay(meters);
        assertThat(relay.accept("u1", "web", Map.of(
                "flags", Map.of(KEY, Map.of("on", side(3, Map.of("checkin_completed", 2)))),
                "events", Map.of("checkin_completed", 2)), T)).isEqualTo(Result.ACCEPTED);
        assertThat(relay.accept("u2", "mobile", Map.of(
                "flags", Map.of(KEY, Map.of("off", side(4, Map.of("checkin_completed", 1)))),
                "events", Map.of("checkin_completed", 1, "kiosk.abandoned", 1)), T)).isEqualTo(Result.ACCEPTED);
        // An app with no flag in use still reports its events.
        assertThat(relay.accept("u3", "web", Map.of("events", Map.of("checkin_completed", 1)), T))
                .isEqualTo(Result.ACCEPTED);
        assertThat(relay.drain()).containsExactlyInAnyOrder(
                FlagCohortRelay.MARKER + " {\"flag\":\"" + KEY + "\",\"source\":\"web\",\"on\":{\"requests\":3,\"errors\":0,"
                        + "\"events\":{\"checkin_completed\":2}},\"off\":{\"requests\":0,\"errors\":0,\"events\":{\"checkin_completed\":0}}}",
                FlagCohortRelay.MARKER + " {\"flag\":\"" + KEY + "\",\"source\":\"mobile\",\"on\":{\"requests\":0,\"errors\":0,"
                        + "\"events\":{\"checkin_completed\":0}},\"off\":{\"requests\":4,\"errors\":0,\"events\":{\"checkin_completed\":1}}}",
                // The SDLC tool's staging check reads this line.
                FlagCohortRelay.EVENTS_MARKER + " {\"checkin_completed\":4,\"kiosk.abandoned\":1}");
        assertThat(relay.drain()).isEmpty();
        assertThat(meters.get(FlagCohortRelay.METER).tag("event", "checkin_completed").tag("source", "web")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void eventCountsNoOnePersonCouldHaveAreRefusedWhole() {
        FlagCohortRelay relay = new FlagCohortRelay();
        assertThat(relay.accept("u", "web", Map.of("events", Map.of("CheckinCompleted", 1)), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of("events", Map.of("checkin_completed", 121)), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of("events", Map.of("checkin_completed", -1)), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of("events", Map.of("checkin_completed", 1.5)), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of("events", "lots"), T)).isEqualTo(Result.INVALID);
        Map<String, Object> many = new java.util.HashMap<>();
        for (int i = 0; i <= FlagCohortRelay.MAX_EVENTS; i++) {
            many.put("event_" + i, 1);
        }
        assertThat(relay.accept("u", "web", Map.of("events", many), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of("flags", Map.of(KEY, Map.of("on", side(1, Map.of("Bad Name", 1))))), T))
                .isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of(), T)).isEqualTo(Result.INVALID);
        assertThat(relay.drain()).isEmpty();
    }
}
