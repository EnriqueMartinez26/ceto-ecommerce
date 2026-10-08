package ar.cetobhb.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.BurstRunner;
import ar.cetobhb.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** Pure GCRA limiter: a {@link MutableClock} drives time, nothing sleeps. */
class TokenBucketLimiterTest {

    private static final Duration INTERVAL = Duration.ofSeconds(360);

    private final MutableClock clock = new MutableClock();

    private TokenBucketLimiter limiter(int burst, Duration interval, int maxKeys) {
        return new TokenBucketLimiter(clock, burst, interval, maxKeys);
    }

    private void advance(Duration time) {
        clock.set(clock.instant().plus(time));
    }

    private static void spend(TokenBucketLimiter limiter, String key, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(limiter.tryAcquire(key).allowed()).as("attempt %d of %s", i + 1, key).isTrue();
        }
    }

    // ---- burst and refill ----

    @Test
    void aFreshKeyGetsExactlyTheBurstThenIsRejectedWithTheWaitForOneToken() {
        TokenBucketLimiter limiter = limiter(10, INTERVAL, 100);

        spend(limiter, "a", 10);
        TokenBucketLimiter.Decision rejected = limiter.tryAcquire("a");

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(360);
    }

    @Test
    void anAllowedDecisionCarriesNoWait() {
        assertThat(limiter(1, INTERVAL, 100).tryAcquire("a")).isEqualTo(new TokenBucketLimiter.Decision(true, 0));
    }

    @Test
    void aBurstOfOneAllowsASingleAttemptPerInterval() {
        TokenBucketLimiter limiter = limiter(1, INTERVAL, 100);

        spend(limiter, "a", 1);

        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        advance(INTERVAL);
        spend(limiter, "a", 1);
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void oneTokenIsGrantedOnlyOnceAFullIntervalHasPassed() {
        TokenBucketLimiter limiter = limiter(2, INTERVAL, 100);
        spend(limiter, "a", 2);

        advance(INTERVAL.minusMillis(1));
        assertThat(limiter.tryAcquire("a").allowed()).as("one millisecond early").isFalse();

        advance(Duration.ofMillis(1));
        spend(limiter, "a", 1);
        assertThat(limiter.tryAcquire("a").allowed()).as("only one token came back").isFalse();
    }

    @Test
    void anIdleKeyNeverAccumulatesMoreThanTheBurst() {
        TokenBucketLimiter limiter = limiter(3, INTERVAL, 100);
        spend(limiter, "a", 3);

        advance(Duration.ofDays(30));

        spend(limiter, "a", 3);
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void retryAfterIsRoundedUpToWholeSecondsAndIsNeverZero() {
        TokenBucketLimiter limiter = limiter(1, Duration.ofMillis(1500), 100);
        spend(limiter, "a", 1);

        advance(Duration.ofMillis(200));
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).as("1300 ms left").isEqualTo(2);

        advance(Duration.ofMillis(300));
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).as("exactly 1000 ms left").isEqualTo(1);

        advance(Duration.ofMillis(999));
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).as("1 ms left").isEqualTo(1);
    }

    @Test
    void rejectedAttemptsDoNotPushTheNextTokenAway() {
        TokenBucketLimiter limiter = limiter(1, INTERVAL, 100);
        spend(limiter, "a", 1);

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        }

        advance(INTERVAL);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        TokenBucketLimiter limiter = limiter(2, INTERVAL, 100);
        spend(limiter, "a", 2);

        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        spend(limiter, "b", 2);
    }

    // ---- clock rewind ----

    @Test
    void aClockRewoundByDaysNeverLocksAKeyOutForLongerThanOneInterval() {
        TokenBucketLimiter limiter = limiter(3, INTERVAL, 100);
        spend(limiter, "a", 3);
        advance(Duration.ofDays(-1));

        TokenBucketLimiter.Decision afterRewind = limiter.tryAcquire("a");

        assertThat(afterRewind.allowed()).isFalse();
        assertThat(afterRewind.retryAfterSeconds()).isEqualTo(360);
        advance(INTERVAL);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void aSmallClockRewindDoesNotGrantExtraTokens() {
        TokenBucketLimiter limiter = limiter(2, INTERVAL, 100);
        spend(limiter, "a", 2);
        advance(Duration.ofSeconds(-10));

        TokenBucketLimiter.Decision afterRewind = limiter.tryAcquire("a");

        assertThat(afterRewind.allowed()).isFalse();
        assertThat(afterRewind.retryAfterSeconds()).isEqualTo(360);
    }

    // ---- bounded memory ----

    @Test
    void trackedKeysNeverExceedTheMaximumAndIdleKeysAreEvictedToMakeRoom() {
        TokenBucketLimiter limiter = limiter(2, Duration.ofSeconds(10), 3);
        for (String key : List.of("a", "b", "c")) {
            spend(limiter, key, 1);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(3);
        advance(Duration.ofSeconds(10));

        spend(limiter, "d", 2);

        assertThat(limiter.trackedKeys()).as("a, b and c were idle and gave way").isEqualTo(1);
        assertThat(limiter.tryAcquire("d").allowed()).as("d owns a bucket of its own").isFalse();
        spend(limiter, "e", 2);
    }

    @Test
    void aKeyThatIsNotIdleIsNeverEvicted() {
        TokenBucketLimiter limiter = limiter(2, Duration.ofSeconds(10), 2);
        spend(limiter, "busy", 2);
        spend(limiter, "idle", 1);
        advance(Duration.ofSeconds(10));

        spend(limiter, "busy", 1);
        spend(limiter, "new", 2);

        assertThat(limiter.tryAcquire("busy").allowed()).as("busy kept its state").isFalse();
        assertThat(limiter.trackedKeys()).as("only the idle key gave way").isEqualTo(2);
    }

    @Test
    void whenNothingIsIdleNewKeysShareOneOverflowBucketAndTrackedKeysAreUnaffected() {
        TokenBucketLimiter limiter = limiter(2, INTERVAL, 2);
        spend(limiter, "a", 2);
        spend(limiter, "b", 1);

        spend(limiter, "x", 2);
        assertThat(limiter.tryAcquire("y").allowed()).as("y shares the overflow bucket with x").isFalse();
        assertThat(limiter.tryAcquire("y").retryAfterSeconds()).isEqualTo(360);

        assertThat(limiter.tryAcquire("a").allowed()).as("a stays rejected, never failing open").isFalse();
        spend(limiter, "b", 1);
        assertThat(limiter.trackedKeys()).isEqualTo(2);
    }

    @Test
    void theOverflowBucketRefillsAndKeysAreTrackedAgainOnceRoomAppears() {
        TokenBucketLimiter limiter = limiter(1, Duration.ofSeconds(10), 1);
        spend(limiter, "a", 1);
        spend(limiter, "x", 1);
        assertThat(limiter.tryAcquire("y").allowed()).isFalse();

        advance(Duration.ofSeconds(10));
        spend(limiter, "y", 1);
        assertThat(limiter.tryAcquire("y").allowed()).as("y now has its own bucket").isFalse();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void theIdleScanRunsAtMostOncePerSecondSoNewKeysCannotForceAScanEachTime() {
        TokenBucketLimiter limiter = limiter(1, Duration.ofMillis(400), 2);
        spend(limiter, "a", 1);
        spend(limiter, "b", 1);

        advance(Duration.ofMillis(400));
        spend(limiter, "c", 1); // the scan runs here and evicts a and b
        spend(limiter, "d", 1);
        advance(Duration.ofMillis(400)); // c and d are idle now, but the scan ran 400 ms ago
        spend(limiter, "e", 1); // full table, scan on cooldown: e takes the overflow token
        assertThat(limiter.tryAcquire("f").allowed()).as("f shares the overflow bucket, no scan yet").isFalse();

        advance(Duration.ofMillis(599)); // 999 ms after the scan
        spend(limiter, "g", 1);
        assertThat(limiter.tryAcquire("h").allowed()).as("still on cooldown: h shares the overflow bucket").isFalse();

        advance(Duration.ofMillis(1)); // exactly 1000 ms after the scan
        spend(limiter, "i", 1);
        spend(limiter, "j", 1); // both tracked: the scan ran and freed c and d
    }

    @Test
    void aClockRewoundBelowTheLastScanDoesNotDisableEviction() {
        TokenBucketLimiter limiter = limiter(1, Duration.ofMillis(100), 1);
        spend(limiter, "a", 1);
        advance(Duration.ofMillis(100));
        spend(limiter, "b", 1);
        advance(Duration.ofDays(-1));
        spend(limiter, "c", 1);
        advance(Duration.ofDays(1).plusMillis(100));

        spend(limiter, "d", 1);
        spend(limiter, "e", 1); // d was tracked (the scan ran again), so e still finds the overflow token
        assertThat(limiter.tryAcquire("d").allowed()).isFalse();
    }

    // ---- concurrency ----

    @Test
    void manyThreadsOnOneKeyNeverGetMoreThanTheBurst() {
        TokenBucketLimiter limiter = limiter(10, INTERVAL, 100);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            tasks.add(() -> limiter.tryAcquire("a").allowed());
        }

        BurstRunner.Report<Boolean> report = BurstRunner.runMeasured(tasks);

        assertThat(report.outcomes()).allMatch(BurstRunner.Outcome::succeeded);
        assertThat(report.outcomes().stream().filter(BurstRunner.Outcome::value).count()).isEqualTo(10);
    }

    @Test
    void manyThreadsOnManyNewKeysNeverGrowTheTableBeyondItsMaximum() {
        int max = 8;
        TokenBucketLimiter limiter = limiter(1, INTERVAL, max);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String key = "key" + i;
            tasks.add(() -> limiter.tryAcquire(key).allowed());
        }

        BurstRunner.Report<Boolean> report = BurstRunner.runMeasured(tasks);

        assertThat(report.outcomes()).allMatch(BurstRunner.Outcome::succeeded);
        assertThat(limiter.trackedKeys()).isEqualTo(max);
        assertThat(report.outcomes().stream().filter(BurstRunner.Outcome::value).count())
                .as("max tracked keys plus the single token of the shared overflow bucket").isEqualTo(max + 1);
    }

    @Test
    void aKeyAdmittedByAnotherThreadWhileThisOneWaitsForTheLockIsDecidedAgainstItsExistingState() throws Exception {
        TokenBucketLimiter limiter = limiter(1, INTERVAL, 10);
        Thread loser;
        TokenBucketLimiter.Decision[] decision = new TokenBucketLimiter.Decision[1];
        synchronized (limiter.admission) {
            loser = new Thread(() -> decision[0] = limiter.tryAcquire("a"));
            loser.start();
            while (loser.getState() != Thread.State.BLOCKED) {
                Thread.onSpinWait(); // the loser saw no state for "a" and now waits for the lock held here
            }
            assertThat(limiter.tryAcquire("a").allowed()).as("this thread admits a first").isTrue();
        }
        loser.join(10_000);

        assertThat(decision[0]).isNotNull();
        assertThat(decision[0].allowed()).as("the token was already spent").isFalse();
        assertThat(decision[0].retryAfterSeconds()).isEqualTo(360);
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    // ---- construction ----

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new TokenBucketLimiter(null, 1, INTERVAL, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TokenBucketLimiter(clock, 1, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> limiter(0, INTERVAL, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, Duration.ofNanos(999_999), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, INTERVAL, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(1, INTERVAL, 1).tryAcquire(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theSmallestValidSettingsWork() {
        TokenBucketLimiter limiter = limiter(1, Duration.ofMillis(1), 1);

        spend(limiter, "a", 1);
        advance(Duration.ofMillis(1));
        spend(limiter, "a", 1);
    }
}
