package ar.cetobhb.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs tasks on their own threads released together by a start latch, so they genuinely contend. A task that does
 * not finish within the timeout fails the test (a hang is a deadlock, never a pass).
 */
public final class BurstRunner {

    private static final long TIMEOUT_SECONDS = 60;

    private BurstRunner() {
    }

    /** Result of one task: exactly one of {@code value} and {@code error} is set. */
    public record Outcome<T>(T value, Throwable error) {

        public boolean succeeded() {
            return error == null;
        }
    }

    /**
     * Outcomes in the order of the tasks plus the maximum number of tasks simultaneously in flight, which proves the
     * burst really overlapped.
     */
    public record Report<T>(List<Outcome<T>> outcomes, int maxInFlight) {
    }

    /** Runs every task concurrently and returns the outcomes in the order of {@code tasks}. */
    public static <T> List<Outcome<T>> run(List<Callable<T>> tasks) {
        return runMeasured(tasks).outcomes();
    }

    /** Like {@link #run} but also reports the maximum number of tasks in flight at the same time. */
    public static <T> Report<T> runMeasured(List<Callable<T>> tasks) {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        ExecutorService pool =Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        return task.call();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }));
            }
            if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("burst threads did not start in time");
            }
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                outcomes.add(await(future));
            }
            return new Report<>(outcomes, maxInFlight.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("burst interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Outcome<T> await(Future<T> future) throws InterruptedException {
        try {
            return new Outcome<>(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            return new Outcome<>(null, e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("burst task did not finish within " + TIMEOUT_SECONDS + " s (deadlock?)", e);
        }
    }
}
