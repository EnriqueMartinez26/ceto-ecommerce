package ar.cetobhb.common.domain;

/**
 * A client spent its attempt allowance (REQ-ABU-02). The HTTP layer maps it to 429 RATE_LIMITED with a
 * {@code Retry-After} header. Carries only the wait: no client address, so no message or log line built from it can
 * leak one (Ley 25.326).
 */
public class RateLimitedException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("RATE_LIMITED");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Whole seconds until the next attempt is allowed, at least 1. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
