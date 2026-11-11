package com.vrixel.bff;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web and mobile apps' side of a gradual release: each app counts, per feature flag, the
 * minutes in which it used the flag and how many of them had a failure (an unhandled error or a
 * failed API call), apart for flag on and off, and posts them here once a minute. The BFF adds
 * them up and logs one line per flag and app a minute, which the SDLC tool compares like the
 * services' own:
 *
 * <pre>{@code vrixel.flag_cohorts {"flag":"emr.emr-feat-3.kiosk","source":"web","on":{"requests":40,"errors":0},"off":{"requests":760,"errors":2}}}</pre>
 *
 * <p>Counts come from browsers and phones, so they're not trusted beyond what one person can do:
 * only signed-in people may post, once a minute each (per replica), with at most {@value #MAX_FLAGS}
 * flags and {@value #MAX_COUNT} per side, and errors never above the total. One person can't swing
 * a comparison that needs hundreds on each side. Only counts are kept: no user, page or error.
 *
 * <p>The apps' product events (the things a feature's goal counts, {@code track("checkin_completed")})
 * come in the same post: per flag and side as {@code "events"}, so a gradual release can compare how
 * often people with a feature on do them, and in total as a top-level {@code "events"}, which the BFF
 * logs once a minute for the tool's staging check and counts on the meter {@code vrixel.events}
 * (attributes {@code event} and {@code source}):
 *
 * <pre>{@code vrixel.events {"checkin_completed":12}}</pre>
 *
 * <p>The same limits apply: at most {@value #MAX_EVENTS} event names a post, each a lowercase name,
 * each count at most {@value #MAX_COUNT}.
 *
 * <p>The BFF checks no flags of its own: it forwards requests to the services, which count them.
 */
public class FlagCohortRelay implements AutoCloseable {

    public static final String MARKER = "vrixel.flag_cohorts";
    public static final Set<String> SOURCES = Set.of("web", "mobile");
    public static final String EVENTS_MARKER = "vrixel.events";
    public static final String METER = "vrixel.events";
    static final int MAX_EVENTS = 20;
    private static final Pattern EVENT = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*");
    static final int MAX_FLAGS = 50;
    static final int MAX_COUNT = 120;
    static final Duration EVERY = Duration.ofSeconds(50);

    private static final Logger log = LoggerFactory.getLogger(FlagCohortRelay.class);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    /** What happened to a post. */
    public enum Result { ACCEPTED, TOO_SOON, INVALID }

    // (source, flag) -> on requests, on errors, off requests, off errors
    private final Map<String, AtomicLongArray> counts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastPost = new ConcurrentHashMap<>();
    // (source, flag) -> event -> on, off
    private final Map<String, Map<String, AtomicLongArray>> flagEvents = new ConcurrentHashMap<>();
    // event -> count since the last summary line, across both apps
    private final Map<String, java.util.concurrent.atomic.AtomicLong> events = new ConcurrentHashMap<>();
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final ScheduledExecutorService reporter;

    /** Counts without reporting (tests); {@link #reporting()} logs once a minute. */
    public FlagCohortRelay() {
        this(null);
    }

    /** Counts without reporting, with the meter (tests). */
    public FlagCohortRelay(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.reporter = null;
    }

    private FlagCohortRelay(long periodSeconds, io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.reporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flag-cohorts");
            t.setDaemon(true);
            return t;
        });
        this.reporter.scheduleAtFixedRate(this::report, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public static FlagCohortRelay reporting() {
        return new FlagCohortRelay(60, null);
    }

    public static FlagCohortRelay reporting(io.micrometer.core.instrument.MeterRegistry meters) {
        return new FlagCohortRelay(60, meters);
    }

    /**
     * One app's counts for its last minute: {@code {"flags": {"<key>": {"on": {"requests": n,
     * "errors": e}, "off": {...}}}}}. Nothing is counted unless the whole post is valid.
     */
    public Result accept(String user, String source, Object body, Instant now) {
        if (user == null || user.isBlank() || !SOURCES.contains(source)) {
            return Result.INVALID;
        }
        if (!(body instanceof Map<?, ?> root)) {
            return Result.INVALID;
        }
        Object rawFlags = root.containsKey("flags") ? root.get("flags") : Map.of();
        Map<String, Long> total = events(root.get("events"));
        if (!(rawFlags instanceof Map<?, ?> flags) || flags.size() > MAX_FLAGS || total == null
                || (flags.isEmpty() && total.isEmpty())) {
            return Result.INVALID;
        }
        List<Object[]> parsed = new ArrayList<>();
        for (Map.Entry<?, ?> e : flags.entrySet()) {
            if (!(e.getKey() instanceof String key) || !KEY.matcher(key).matches() || !(e.getValue() instanceof Map<?, ?> sides)) {
                return Result.INVALID;
            }
            long[] c = new long[4];
            Map<String, Long> on = sides.get("on") instanceof Map<?, ?> m ? events(m.get("events")) : Map.of();
            Map<String, Long> off = sides.get("off") instanceof Map<?, ?> m ? events(m.get("events")) : Map.of();
            if (!side(sides.get("on"), c, 0) || !side(sides.get("off"), c, 2) || on == null || off == null) {
                return Result.INVALID;
            }
            parsed.add(new Object[] {key, c, on, off});
        }
        String who = source + ":" + user;
        Instant last = lastPost.get(who);
        if (last != null && Duration.between(last, now).compareTo(EVERY) < 0) {
            return Result.TOO_SOON;
        }
        lastPost.put(who, now);
        for (Object[] p : parsed) {
            long[] c = (long[]) p[1];
            String id = source + "\n" + p[0];
            AtomicLongArray into = counts.computeIfAbsent(id, k -> new AtomicLongArray(4));
            for (int i = 0; i < 4; i++) {
                into.addAndGet(i, c[i]);
            }
            for (int s = 0; s < 2; s++) {
                @SuppressWarnings("unchecked")
                Map<String, Long> ev = (Map<String, Long>) p[2 + s];
                if (!ev.isEmpty()) {
                    int at = s;
                    Map<String, AtomicLongArray> byEvent = flagEvents.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
                    ev.forEach((name, n) -> byEvent.computeIfAbsent(name, x -> new AtomicLongArray(2)).addAndGet(at, n));
                }
            }
        }
        total.forEach((name, n) -> {
            events.computeIfAbsent(name, x -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(n);
            if (meters != null) {
                io.micrometer.core.instrument.Counter.builder(METER).description("Product events the feature goals count")
                        .tag("event", name).tag("source", source).register(meters).increment(n);
            }
        });
        return Result.ACCEPTED;
    }

    /** An app's event counts: {"name": n}, or null when they aren't valid. Absent is none. */
    private static Map<String, Long> events(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> m) || m.size() > MAX_EVENTS) {
            return null;
        }
        Map<String, Long> out = new java.util.TreeMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String name) || name.length() > 100 || !EVENT.matcher(name).matches()
                    || !(e.getValue() instanceof Number n) || n.doubleValue() != n.longValue()
                    || n.longValue() < 0 || n.longValue() > MAX_COUNT) {
                return null;
            }
            out.put(name, n.longValue());
        }
        return out;
    }

    private static boolean side(Object value, long[] into, int at) {
        if (value == null) {
            return true; // this person only saw the other side
        }
        if (!(value instanceof Map<?, ?> m) || !(m.get("requests") instanceof Number n)) {
            return false;
        }
        Object err = m.containsKey("errors") ? m.get("errors") : Integer.valueOf(0);
        if (!(err instanceof Number e)) {
            return false;
        }
        long requests = n.longValue();
        long errors = e.longValue();
        if (requests < 0 || requests > MAX_COUNT || errors < 0 || errors > requests
                || n.doubleValue() != requests || e.doubleValue() != errors) {
            return false;
        }
        into[at] = requests;
        into[at + 1] = errors;
        return true;
    }

    /** The lines since the last call, one per flag and app, and the counts start again. */
    public List<String> drain() {
        List<String> lines = new ArrayList<>();
        for (String id : List.copyOf(counts.keySet())) {
            AtomicLongArray c = counts.remove(id);
            if (c == null) {
                continue;
            }
            String[] parts = id.split("\n", 2);
            Map<String, AtomicLongArray> ev = flagEvents.remove(id);
            lines.add(MARKER + " {\"flag\":\"" + parts[1] + "\",\"source\":\"" + parts[0] + "\",\"on\":{\"requests\":"
                    + c.get(0) + ",\"errors\":" + c.get(1) + sideEvents(ev, 0) + "},\"off\":{\"requests\":" + c.get(2)
                    + ",\"errors\":" + c.get(3) + sideEvents(ev, 1) + "}}");
        }
        java.util.TreeMap<String, Long> total = new java.util.TreeMap<>();
        for (String name : List.copyOf(events.keySet())) {
            java.util.concurrent.atomic.AtomicLong n = events.remove(name);
            if (n != null && n.get() > 0) {
                total.put(name, n.get());
            }
        }
        if (!total.isEmpty()) {
            List<String> parts = new ArrayList<>();
            total.forEach((k, v) -> parts.add("\"" + k + "\":" + v));
            lines.add(EVENTS_MARKER + " {" + String.join(",", parts) + "}");
        }
        return lines;
    }

    private static String sideEvents(Map<String, AtomicLongArray> ev, int side) {
        if (ev == null || ev.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        new java.util.TreeMap<>(ev).forEach((name, n) -> parts.add("\"" + name + "\":" + n.get(side)));
        return ",\"events\":{" + String.join(",", parts) + "}";
    }

    /** Forget who posted long ago, so the map stays as small as the people using the apps now. */
    void forget(Instant now) {
        lastPost.values().removeIf(t -> Duration.between(t, now).compareTo(EVERY.multipliedBy(2)) > 0);
    }

    void report() {
        try {
            drain().forEach(log::info);
            forget(Instant.now());
        } catch (RuntimeException e) {
            log.warn("flag cohorts not reported: {}", e.toString());
        }
    }

    @Override
    public void close() {
        if (reporter != null) {
            reporter.shutdown();
            report();
        }
    }
}
