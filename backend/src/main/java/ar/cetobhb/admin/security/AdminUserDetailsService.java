package ar.cetobhb.admin.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * The account store: exactly one account, the one in {@code ceto.admin.*} (REQ-AUTH-01, REQ-AUTH-03, design AD-A5).
 *
 * <p>The name is compared with {@link MessageDigest#isEqual} so the comparison time does not depend on how many leading
 * characters match, and a name longer than 128 characters is refused before anything else. Every miss is the same
 * {@link UsernameNotFoundException}, which {@code DaoAuthenticationProvider} turns into the same
 * {@code BadCredentialsException} as a wrong password. A new {@link User} is built for each call: the framework erases
 * the credentials of the principal after a login, so a shared instance would lose its password.
 */
public final class AdminUserDetailsService implements UserDetailsService {

    /** Longest name looked at; the configured name is at most 64 characters. */
    static final int MAX_LOOKUP_LENGTH = 128;

    static final String ROLE = "ADMIN";

    private final byte[] usernameBytes;
    private final String username;
    private final String bcryptHash;

    public AdminUserDetailsService(AdminCredentials credentials) {
        this.username = credentials.username();
        this.usernameBytes = credentials.username().getBytes(StandardCharsets.UTF_8);
        this.bcryptHash = credentials.bcryptHash();
    }

    @Override
    public UserDetails loadUserByUsername(String candidate) throws UsernameNotFoundException {
        if (candidate == null || candidate.length() > MAX_LOOKUP_LENGTH
                || !MessageDigest.isEqual(usernameBytes, candidate.getBytes(StandardCharsets.UTF_8))) {
            throw new UsernameNotFoundException("Unknown administrator");
        }
        return User.withUsername(username).password(bcryptHash).roles(ROLE).build();
    }
}
