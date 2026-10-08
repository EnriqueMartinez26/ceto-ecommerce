package ar.cetobhb.admin.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * The bound on the two login fields (REQ-AUTH-02), applied before the real manager is asked: a username or a password
 * of more than {@value #MAX_FIELD_CHARS} characters is refused here, so neither the account store nor BCrypt ever sees
 * it. The refusal is the same {@link BadCredentialsException} (fixed message, no cause, no value) as a wrong password,
 * so the login answers the one neutral 401 whichever field was too long. The body of the request is already bounded by
 * {@code ceto.web.max-body-bytes}; this caps what is left of it.
 *
 * <p>This is the first of two guards: {@link AdminPasswordEncoder} still refuses a password of more than 72 UTF-8 bytes
 * without hashing, which this character cap cannot express.
 */
final class LoginFieldCapAuthenticationManager implements AuthenticationManager {

    /** Longest username or password, in characters (UTF-16 units). */
    static final int MAX_FIELD_CHARS = 128;

    private final AuthenticationManager delegate;

    LoginFieldCapAuthenticationManager(AuthenticationManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (authentication instanceof UsernamePasswordAuthenticationToken
                && (tooLong(authentication.getPrincipal()) || tooLong(authentication.getCredentials()))) {
            throw new BadCredentialsException("Bad credentials");
        }
        return delegate.authenticate(authentication);
    }

    private static boolean tooLong(Object field) {
        return field instanceof CharSequence text && text.length() > MAX_FIELD_CHARS;
    }
}
