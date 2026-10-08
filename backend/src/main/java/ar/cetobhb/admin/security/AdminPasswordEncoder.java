package ar.cetobhb.admin.security;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt for the one administrator password, with the input bounded (REQ-AUTH-02, design AD-A5).
 *
 * <p>BCrypt only reads the first 72 bytes of a password. The stored password is provisioned within that limit, so a
 * longer candidate can never be the right one: answering {@code false} without hashing removes both the truncation (a
 * 73-byte password matching its own 72-byte prefix) and the cost of hashing an attacker-sized string. {@code encode}
 * is only reached for {@code DaoAuthenticationProvider}'s fixed timing-protection dummy.
 *
 * <p>Deliberately not a {@code DelegatingPasswordEncoder}: the only accepted format is the one
 * {@link AdminCredentials} validated at startup.
 */
public final class AdminPasswordEncoder implements PasswordEncoder {

    /** The number of password bytes BCrypt takes into account. */
    static final int MAX_PASSWORD_BYTES = 72;

    private final PasswordEncoder inner;

    public AdminPasswordEncoder(PasswordEncoder inner) {
        this.inner = inner;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return inner.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || exceedsBcryptInput(rawPassword)) {
            return false;
        }
        return inner.matches(rawPassword, encodedPassword);
    }

    /** Every UTF-16 unit takes at least one byte, so more than 72 units is already more than 72 bytes. */
    private static boolean exceedsBcryptInput(CharSequence raw) {
        return raw.length() > MAX_PASSWORD_BYTES
                || raw.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return false;
    }
}
