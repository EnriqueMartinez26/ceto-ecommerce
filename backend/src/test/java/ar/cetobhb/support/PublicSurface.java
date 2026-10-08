package ar.cetobhb.support;

import java.util.Set;

/**
 * The response header NAMES of the public API as they were before Spring Security joined the application (captured on
 * feat/admin-facade-catalog, f361438, 2026-10-08). Security's default writers would add {@code X-Frame-Options},
 * {@code X-XSS-Protection}, {@code Pragma}, {@code Expires} and a second {@code Cache-Control}; the public surface must
 * stay exactly as it was (REQ-HRD-09), so any change here is a decision, not a refactor.
 */
public final class PublicSurface {

    private PublicSurface() {
    }

    /** Header names the container and the HTTP client add on a real socket; irrelevant to the application contract. */
    public static final Set<String> TRANSPORT_HEADERS = Set.of("date", "content-length", "connection", "keep-alive",
            "transfer-encoding");

    /** Headers that would prove Spring Security wrote on a public response. */
    public static final Set<String> SECURITY_DEFAULTS = Set.of("x-frame-options", "x-xss-protection", "pragma",
            "expires", "strict-transport-security", "set-cookie");

    private static final Set<String> BASE = Set.of("content-security-policy", "content-type", "referrer-policy",
            "x-content-type-options", "x-request-id");
    private static final Set<String> NO_STORE = Set.of("cache-control", "content-security-policy", "content-type",
            "referrer-policy", "x-content-type-options", "x-request-id");

    public static final Set<String> LIST = BASE;
    public static final Set<String> DETAIL = BASE;
    public static final Set<String> CHECKOUT_201 = NO_STORE;
    public static final Set<String> CHECKOUT_200_REPLAY = NO_STORE;
    public static final Set<String> ERROR_404 = NO_STORE;
}
