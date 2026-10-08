package ar.cetobhb.admin.security;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Holder of the raw BCrypt work of the administrator account, at the cost of the stored hash.
 *
 * <p>It is deliberately NOT a {@link PasswordEncoder}: a raw BCrypt encoder registered as a bean would answer any
 * injection by type and bypass the 72-byte guard of {@link AdminPasswordEncoder}, which must be the only
 * {@code PasswordEncoder} in the context. Tests replace this holder (by bean name) with one that counts the hashes
 * really computed.
 *
 * @param encoder the raw encoder; only {@link AdminPasswordEncoder} should use it
 */
public record AdminBcrypt(PasswordEncoder encoder) {

    @Override
    public String toString() {
        return "AdminBcrypt[redacted]";
    }
}
