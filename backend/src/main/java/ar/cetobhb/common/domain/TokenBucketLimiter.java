package ar.cetobhb.common.domain;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key rate limiter using the generic cell rate algorithm (GCRA), a token bucket that stores one {@code long}
 * per key: the theoretical arrival time (TAT) of the next token, in epoch milliseconds. A key may spend {@code burst}
 * attempts at once and then one more every {@code refillInterval}. Every attempt that is allowed spends a token;
 * a rejected one costs nothing and does not push the next token away.
 *
 * <p>Pure and deterministic: time comes only from the injected {@link Clock} (CLAUDE.md rule 21). Thread-safe: the
 * state of a key changes in one atomic map operation.
 *
 * <p>Memory is bounded by {@code maxTrackedKeys}. A key whose bucket is full again (TAT in the past) is
 * indistinguishable from an unknown one, so it is evicted first when the table fills up; that scan runs at most once
 * per second so a flood of new keys cannot force a full scan per request. When nothing can be evicted, new keys share
 * ONE overflow bucket: untracked clients are limited together (fail closed) while tracked ones are never affected.
 *
 * <p>After a clock rewind a TAT can lie further in the future than any allowed sequence could have produced; it is
 * clamped to that maximum so a key is never locked out for longer than one refill interval by a clock step.
 *
 * <p>Keys are opaque and are never logged here.
 */
public final class TokenBucketLimiter {

    /** Outcome of one attempt. {@code retryAfterSeconds} is 0 when allowed, otherwise whole seconds, at least 1. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private static final Decision ALLOWED = new Decision(true, 0);
    private static final long PURGE_COOLDOWN_MS = 1_000;
    private static final long MILLIS_PER_SECOND = 1_000;

    /** New TAT of the key plus the decision. */
    private record Verdict(Decision decision, long tat) {
    }

    private final Clock clock;
    private final long intervalMs;
    private final long toleranceMs;
    private final int maxTrackedKeys;
    private final ConcurrentHashMap<String, Long> tats = new ConcurrentHashMap<>();
    /** Guards every insertion of a new key, the idle scan and the overflow bucket. Known keys never take it. */
    final Object admission = new Object(); // package-private: a test holds it to force the lost-race branch
    private long lastScanMs;
    private Long overflowTat;

    public TokenBucketLimiter(Clock clock, int burst, Duration refillInterval, int maxTrackedKeys) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(refillInterval, "refillInterval");
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be at least 1");
        }
        if (refillInterval.toMillis() < 1) {
            throw new IllegalArgumentException("refillInterval must be at least 1 ms");
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys must be at least 1");
        }
        this.intervalMs = refillInterval.toMillis();
        this.toleranceMs = Math.multiplyExact(intervalMs, burst - 1L);
        this.maxTrackedKeys = maxTrackedKeys;
    }

    /** Spends one token of {@code key} if it has one. Never throws for load, never fails open for tracked keys. */
    public Decision tryAcquire(String key) {
        Objects.requireNonNull(key, "key");
        long now = clock.instant().toEpochMilli();
        Decision known = attemptTracked(key, now);
        if (known != null) {
            return known;
        }
        synchronized (admission) {
            known = attemptTracked(key, now);
            if (known != null) {
                return known;
            }
            if (tats.size() >= maxTrackedKeys) {
                evictIdle(now);
            }
            if (tats.size() < maxTrackedKeys) {
                // A key with no state always has a full bucket, so its first attempt is allowed.
                tats.put(key, now + intervalMs);
                return ALLOWED;
            }
            Verdict verdict = evaluate(overflowTat, now);
            overflowTat = verdict.tat();
            return verdict.decision();
        }
    }

    /** Number of keys with their own bucket; never above {@code maxTrackedKeys}. */
    public int trackedKeys() {
        return tats.size();
    }

    /** The decision for a key that has state, or null when the key is not tracked. */
    private Decision attemptTracked(String key, long now) {
        Decision[] decision = new Decision[1];
        tats.computeIfPresent(key, (k, tat) -> {
            Verdict verdict = evaluate(tat, now);
            decision[0] = verdict.decision();
            return verdict.tat();
        });
        return decision[0];
    }

    private void evictIdle(long now) {
        // Unsigned compare: a clock rewind makes the difference negative, i.e. huge, so the scan is allowed again.
        if (Long.compareUnsigned(now - lastScanMs, PURGE_COOLDOWN_MS) < 0) {
            return;
        }
        lastScanMs = now;
        tats.forEach((key, tat) -> {
            if (tat <= now) {
                tats.remove(key, tat);
            }
        });
    }

    private Verdict evaluate(Long storedTat, long now) {
        long ceiling = now + toleranceMs + intervalMs;
        long start = storedTat == null ? now : Math.max(Math.min(storedTat, ceiling), now);
        long ahead = start - now;
        if (ahead > toleranceMs) {
            long waitMs = ahead - toleranceMs;
            return new Verdict(new Decision(false, Math.ceilDiv(waitMs, MILLIS_PER_SECOND)), start);
        }
        return new Verdict(ALLOWED, start + intervalMs);
    }
}
