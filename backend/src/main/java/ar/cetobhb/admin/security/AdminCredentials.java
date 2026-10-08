package ar.cetobhb.admin.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single administrator credential, validated with FIXED messages (REQ-AUTH-01, design AD-A4).
 *
 * <p>The values are bound as plain strings and checked here, not with Bean Validation: Boot's bind and validation
 * failure report prints the rejected value, and a startup log that shows {@code {noop}secret} is a leak. The messages
 * below name the property and never the value, and the exceptions have no cause that could carry it.
 *
 * <p>Only a {@code {bcrypt}} hash of cost 10 to 14 is accepted. There is no {@code DelegatingPasswordEncoder} behind
 * it, so an unknown prefix cannot surprise a login at run time: it stops the startup instead.
 */
public final class AdminCredentials {

    public static final String INVALID_USERNAME = "ceto.admin.username (ADMIN_USERNAME) is required: 1 to 64 "
            + "characters with no whitespace, control or format characters";

    public static final String INVALID_PASSWORD_HASH = "ceto.admin.password-hash (ADMIN_PASSWORD_HASH) is required "
            + "and must be a {bcrypt} hash of cost 10 to 14";

    static final int MAX_USERNAME_LENGTH = 64;

    /** Longest hash that can match: "{bcrypt}" + "$2a$12$" + 53 characters. Longer input is not even matched. */
    private static final int MAX_HASH_LENGTH = 8 + 7 + 53;

    private static final Pattern BCRYPT = Pattern.compile("\\{bcrypt}(\\$2[aby]\\$(1[0-4])\\$[./A-Za-z0-9]{53})");

    private final String username;
    private final String bcryptHash;
    private final int cost;

    private AdminCredentials(String username, String bcryptHash, int cost) {
        this.username = username;
        this.bcryptHash = bcryptHash;
        this.cost = cost;
    }

    /**
     * @throws IllegalStateException with a fixed message when either value is missing, blank, unsafe or not a
     *                               {@code {bcrypt}} hash of cost 10 to 14
     */
    public static AdminCredentials parse(String username, String passwordHash) {
        if (!isValidUsername(username)) {
            throw new IllegalStateException(INVALID_USERNAME);
        }
        if (passwordHash == null || passwordHash.length() > MAX_HASH_LENGTH) {
            throw new IllegalStateException(INVALID_PASSWORD_HASH);
        }
        Matcher match = BCRYPT.matcher(passwordHash);
        if (!match.matches()) {
            throw new IllegalStateException(INVALID_PASSWORD_HASH);
        }
        return new AdminCredentials(username, match.group(1), Integer.parseInt(match.group(2)));
    }

    private static boolean isValidUsername(String username) {
        if (username == null || username.isEmpty() || username.length() > MAX_USERNAME_LENGTH) {
            return false;
        }
        // Boot's binder leaves a placeholder it cannot resolve ("${ADMIN_USERNAME}") in the value instead of failing, so
        // an unset variable would otherwise become a valid-looking account name.
        if (username.contains("${")) {
            return false;
        }
        return username.codePoints().noneMatch(AdminCredentials::isForbiddenInUsername);
    }

    private static boolean isForbiddenInUsername(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.SURROGATE
                || Character.getType(codePoint) == Character.UNASSIGNED;
    }

    public String username() {
        return username;
    }

    /** The hash without the {@code {bcrypt}} prefix, as BCrypt reads it. */
    public String bcryptHash() {
        return bcryptHash;
    }

    /** The BCrypt work factor of the stored hash. */
    public int cost() {
        return cost;
    }

    @Override
    public String toString() {
        return "AdminCredentials[redacted]";
    }
}
