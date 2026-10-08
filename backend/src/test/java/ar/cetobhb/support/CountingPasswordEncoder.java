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

    @Override
    public String encode(CharSequence rawPassword) {
        ENCODES.incrementAndGet();
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        MATCHES.incrementAndGet();
        return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }
}
