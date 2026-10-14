package ar.cetobhb.admin.security;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * The CPU cap of the login (D-20261008-45): at most {@code permits} password verifications run at the same time, across
 * every client. A BCrypt comparison is the one expensive step of the login, and the per-address limiter admits a burst
 * from each of many addresses; this cap bounds the work the server does at once whatever the number of addresses. An
 * attempt that finds every slot taken waits at most {@value #WAIT_MILLIS} ms and is then refused with
 * {@link LoginBusyException} BEFORE the real manager is asked, so no hash is computed and no session is opened; the
 * failure handler turns it into a 503 {@code SERVICE_BUSY} with {@code Retry-After: 1}.
 *
 * <p>This is a concurrency cap, not an attempt limit: it never remembers who tried, counts nothing over time and locks
 * nothing, so it does not contradict D-20261008-07 (no lockout per account, no global login bucket). The attempt's
 * token in the per-address bucket was already spent; that is intended. The slot is always given back, whatever the real
 * manager does.
 *
 * <p>It sits below {@link LoginFieldCapAuthenticationManager}: an over-long field is refused before it takes a slot.
 */
final class LoginVerificationCapAuthenticationManager implements AuthenticationManager {

    /** How long an attempt waits for a free slot before it is refused, in milliseconds. */
    static final long WAIT_MILLIS = 200;

    private final AuthenticationManager delegate;
    private final Semaphore slots;

    LoginVerificationCapAuthenticationManager(AuthenticationManager delegate, int permits) {
        this.delegate = delegate;
        this.slots = new Semaphore(permits);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LoginBusyException();
        }
        if (!acquired) {
            throw new LoginBusyException();
        }
        try {
            return delegate.authenticate(authentication);
        } finally {
            slots.release();
        }
    }

    /** The cap was full: not a bad credential, a busy server. Fixed message, no request value. */
    static final class LoginBusyException extends AuthenticationException {
        private static final long serialVersionUID = 1L;

        LoginBusyException() {
            super("Login verification capacity is busy");
        }
    }
}
