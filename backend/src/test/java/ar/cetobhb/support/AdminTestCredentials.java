package ar.cetobhb.support;

import java.security.SecureRandom;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The administrator credential of the test JVM (design AD-A19). The password is random and the hash is computed at
 * run time, once per JVM, so no hash (and no password) is ever committed: {@code NoCommittedHashTest} scans the
 * sources for one. {@code TestcontainersConfiguration} supplies these values as {@code ceto.admin.*} to every Spring
 * context that imports it.
 */
public final class AdminTestCredentials {

    /** Not a secret: the account name only has to be valid and stable. */
    public static final String USERNAME = "test-admin";

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int PASSWORD_LENGTH = 24;
    /** The lowest cost the application accepts. */
    private static final int COST = 10;

    private static final String PASSWORD = randomPassword();
    private static final String HASH = "{bcrypt}" + new BCryptPasswordEncoder(COST).encode(PASSWORD);

    private AdminTestCredentials() {
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    public static String username() {
        return USERNAME;
    }

    /** The clear password, for tests that log in. */
    public static String password() {
        return PASSWORD;
    }

    /** The {@code {bcrypt}} value for {@code ceto.admin.password-hash}. */
    public static String passwordHash() {
        return HASH;
    }
}
