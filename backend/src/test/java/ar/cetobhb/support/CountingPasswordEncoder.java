package ar.cetobhb.support;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Counts the BCrypt work of the administrator account. It wraps the real BCrypt encoder (the inner one, below the
 * 72-byte guard), so an attempt that the guard refuses does not move the counter: that is what lets a test say "this
 * request cost no hash" or "N admitted attempts cost N hashes".
 */
public final class CountingPasswordEncoder implements PasswordEncoder {

    private static final AtomicInteger MATCHES = new AtomicInteger();
    private static final AtomicInteger ENCODES = new AtomicInteger();

    private static volatile Hold hold;

    private final PasswordEncoder delegate;

    public CountingPasswordEncoder(PasswordEncoder delegate) {
        this.delegate = delegate;
    }

    /** Comparisons computed so far in this JVM (static: the encoder is built per Spring context). */
    public static int matches() {
        return MATCHES.get();
    }

    public static int encodes() {
        return ENCODES.get();
    }

    /**
     * Makes every comparison block, after it was counted and before BCrypt runs, until the returned hold is released
     * (or closed). It lets a test keep N verifications in flight on the real server. Only one hold at a time; close it
     * in a finally block.
     */
    public static Hold holdMatches() {
        Hold created = new Hold();
        hold = created;
        return created;
    }

    /** The comparisons that are blocked in {@link #holdMatches()}. */
    public static final class Hold implements AutoCloseable {

        private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        private final AtomicInteger blocked = new AtomicInteger();

        /** Waits (up to 10 s) until {@code count} comparisons are blocked. */
        public void awaitBlocked(int count) throws InterruptedException {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (blocked.get() < count && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            if (blocked.get() < count) {
                throw new AssertionError("only " + blocked.get() + " comparisons blocked, expected " + count);
            }
        }

        public void release() {
            released.countDown();
        }

        @Override
        public void close() {
            release();
            if (hold == this) {
                hold = null;
            }
        }

        void block() {
            blocked.incrementAndGet();
            try {
                released.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String encode(CharSequence rawPassword) {
        ENCODES.incrementAndGet();
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        MATCHES.incrementAndGet();
        Hold current = hold;
        if (current != null) {
            current.block();
        }
        return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }
}
