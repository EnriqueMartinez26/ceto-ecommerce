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
 * ONE overflow bucket with its OWN budget (normally larger than a single client's): untracked clients are limited
 * together (fail closed) while tracked ones are never affected. Under a sustained distributed flood that fills the
 * table, new visitors can therefore get 429 until the overflow bucket refills.
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

    /** Largest accepted burst; far above any sane setting, it keeps every product below inside {@code long}. */
    static final int MAX_BURST = 100_000;
    /** Largest accepted refill interval. */
    static final Duration MAX_REFILL_INTERVAL = Duration.ofDays(1);

    private static final Decision ALLOWED = new Decision(true, 0);
    private static final long PURGE_COOLDOWN_MS = 1_000;
    private static final long MILLIS_PER_SECOND = 1_000;

    /** New TAT of the key plus the decision. */
    private record Verdict(Decision decision, long tat) {
    }

    /** Burst allowance and refill pace of one bucket, in milliseconds. */
    private record Budget(long intervalMs, long toleranceMs, long ceilingOffsetMs) {

        static Budget of(String burstName, int burst, String intervalName, Duration refillInterval) {
            Objects.requireNonNull(refillInterval, intervalName);
            if (burst < 1 || burst > MAX_BURST) {
                throw new IllegalArgumentException(burstName + " must be between 1 and " + MAX_BURST);
            }
            if (refillInterval.compareTo(Duration.ofMillis(1)) < 0
                    || refillInterval.compareTo(MAX_REFILL_INTERVAL) > 0) {
                throw new IllegalArgumentException(
                        intervalName + " must be between 1 ms and " + MAX_REFILL_INTERVAL);
            }
            long intervalMs = refillInterval.toMillis();
            long toleranceMs = Math.multiplyExact(intervalMs, burst - 1L);
            return new Budget(intervalMs, toleranceMs, Math.addExact(toleranceMs, intervalMs));
        }
    }

    private final Clock clock;
    private final Budget client;
    private final Budget overflow;
    private final int maxTrackedKeys;
    private final ConcurrentHashMap<String, Long> tats = new ConcurrentHashMap<>();
    /** Guards every insertion of a new key, the idle scan and the overflow bucket. Known keys never take it. */
    final Object admission = new Object(); // package-private: a test holds it to force the lost-race branch
    private long lastScanMs;
    /** Number of idle scans run so far; package-private so a test can prove the cooldown holds. */
    int scans; // guarded by admission
    private Long overflowTat;

    /**
     * @param burst            attempts a client may spend at once (1 to {@value #MAX_BURST})
     * @param refillInterval   time to earn one attempt back (1 ms to one day)
     * @param maxTrackedKeys   clients with a bucket of their own
     * @param overflowBurst    same as {@code burst} for the ONE bucket shared by every client that finds the table full
     * @param overflowInterval same as {@code refillInterval} for the shared overflow bucket
     */
    public TokenBucketLimiter(Clock clock, int burst, Duration refillInterval, int maxTrackedKeys,
            int overflowBurst, Duration overflowInterval) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.client = Budget.of("burst", burst, "refillInterval", refillInterval);
        this.overflow = Budget.of("overflowBurst", overflowBurst, "overflowInterval", overflowInterval);
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("maxTrackedKeys must be at least 1");
        }
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
            // Waiting for the lock can take long under contention: the time read before it is stale here and would
            // defeat the scan cooldown (a later thread could carry an older "now") and date the TATs too early.
            long lockedNow = clock.instant().toEpochMilli();
            known = attemptTracked(key, lockedNow);
            if (known != null) {
                return known;
            }
            if (tats.size() >= maxTrackedKeys) {
                evictIdle(lockedNow);
            }
            if (tats.size() < maxTrackedKeys) {
                // A key with no state always has a full bucket, so its first attempt is allowed.
                tats.put(key, lockedNow + client.intervalMs());
                return ALLOWED;
            }
            Verdict verdict = evaluate(overflow, overflowTat, lockedNow);
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
            Verdict verdict = evaluate(client, tat, now);
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
        scans++;
        tats.forEach((key, tat) -> {
            if (tat <= now) {
                tats.remove(key, tat);
            }
        });
    }

    private static Verdict evaluate(Budget budget, Long storedTat, long now) {
        long ceiling = now + budget.ceilingOffsetMs();
        long start = storedTat == null ? now : Math.max(Math.min(storedTat, ceiling), now);
        long ahead = start - now;
        if (ahead > budget.toleranceMs()) {
            long waitMs = ahead - budget.toleranceMs();
            return new Verdict(new Decision(false, Math.ceilDiv(waitMs, MILLIS_PER_SECOND)), start);
        }
        return new Verdict(ALLOWED, start + budget.intervalMs());
    }
}
