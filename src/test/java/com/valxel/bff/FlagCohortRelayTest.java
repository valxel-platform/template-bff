package com.valxel.bff;

import static org.assertj.core.api.Assertions.assertThat;

import com.valxel.bff.FlagCohortRelay.Result;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagCohortRelayTest {

    private static final String KEY = "emr.emr-feat-3.check-in-kiosk";
    private static final Instant T = Instant.parse("2026-11-06T10:00:00Z");

    private static Map<String, Object> post(Object on, Object off) {
        Map<String, Object> sides = new java.util.HashMap<>();
        if (on != null) {
            sides.put("on", on);
        }
        if (off != null) {
            sides.put("off", off);
        }
        return Map.of("flags", Map.of(KEY, sides));
    }

    private static Map<String, Object> side(Object requests, Object errors) {
        return Map.of("requests", requests, "errors", errors);
    }

    @Test
    void theAppsMinutesAddUpPerFlagAndApp() {
        FlagCohortRelay relay = new FlagCohortRelay();
        assertThat(relay.accept("u1", "web", post(side(3, 1), null), T)).isEqualTo(Result.ACCEPTED);
        assertThat(relay.accept("u2", "web", post(side(2, 0), side(0, 0)), T)).isEqualTo(Result.ACCEPTED);
        assertThat(relay.accept("u3", "web", post(null, side(4, 2)), T)).isEqualTo(Result.ACCEPTED);
        assertThat(relay.accept("u1", "mobile", post(null, side(1, 0)), T)).isEqualTo(Result.ACCEPTED);
        assertThat(relay.drain()).containsExactlyInAnyOrder(
                FlagCohortRelay.MARKER + " {\"flag\":\"" + KEY + "\",\"source\":\"web\",\"on\":{\"requests\":5,\"errors\":1},"
                        + "\"off\":{\"requests\":4,\"errors\":2}}",
                FlagCohortRelay.MARKER + " {\"flag\":\"" + KEY + "\",\"source\":\"mobile\",\"on\":{\"requests\":0,\"errors\":0},"
                        + "\"off\":{\"requests\":1,\"errors\":0}}");
        assertThat(relay.drain()).isEmpty();
    }

    @Test
    void onePersonPostsOnceAMinute() {
        FlagCohortRelay relay = new FlagCohortRelay();
        assertThat(relay.accept("u1", "web", post(side(1, 0), null), T)).isEqualTo(Result.ACCEPTED);
        assertThat(relay.accept("u1", "web", post(side(1, 0), null), T.plusSeconds(10))).isEqualTo(Result.TOO_SOON);
        assertThat(relay.accept("u1", "mobile", post(side(1, 0), null), T.plusSeconds(10))).isEqualTo(Result.ACCEPTED);
        assertThat(relay.accept("u1", "web", post(side(1, 0), null), T.plusSeconds(55))).isEqualTo(Result.ACCEPTED);
        relay.forget(T.plusSeconds(400));
        assertThat(relay.accept("u1", "web", post(side(1, 0), null), T.plusSeconds(401))).isEqualTo(Result.ACCEPTED);
    }

    @Test
    void countsNoOnePersonCouldHaveAreRefusedWhole() {
        FlagCohortRelay relay = new FlagCohortRelay();
        assertThat(relay.accept("u", "web", post(side(121, 0), null), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", post(side(2, 3), null), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", post(side(-1, 0), null), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", post(side(1.5, 0), null), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", post(side("3", 0), null), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of("flags", Map.of("not a key!", Map.of())), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "web", Map.of("flags", Map.of()), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("u", "desktop", post(side(1, 0), null), T)).isEqualTo(Result.INVALID);
        assertThat(relay.accept("", "web", post(side(1, 0), null), T)).isEqualTo(Result.INVALID);
        Map<String, Object> many = new java.util.HashMap<>();
        for (int i = 0; i < 51; i++) {
            many.put("f" + i, Map.of("on", side(1, 0)));
        }
        assertThat(relay.accept("u", "web", Map.of("flags", many), T)).isEqualTo(Result.INVALID);
        // A post with one bad flag counts none of them, and doesn't use up the minute.
        assertThat(relay.accept("u", "web", Map.of("flags", Map.of(KEY, Map.of("on", side(1, 0)), "x", Map.of("on", side(500, 0)))), T))
                .isEqualTo(Result.INVALID);
        assertThat(relay.drain()).isEmpty();
        assertThat(relay.accept("u", "web", post(side(1, 0), null), T)).isEqualTo(Result.ACCEPTED);
    }

    @Test
    void theReportingRelayLogsWhatItHasWhenItStops() {
        FlagCohortRelay reporting = FlagCohortRelay.reporting();
        reporting.accept("u", "web", post(side(1, 0), null), T);
        reporting.close();
        assertThat(reporting.drain()).isEmpty();
    }
}
