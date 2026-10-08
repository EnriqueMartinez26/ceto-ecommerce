package ar.cetobhb.admin.config;

/** Builders for {@link AdminProperties} in unit tests that only care about some of the settings. */
public final class AdminPropertiesFixtures {

    private AdminPropertiesFixtures() {
    }

    /** Shipped defaults for everything except the two list caps; no credential. */
    public static AdminProperties withCaps(int maxListedProducts, int maxReconciliationRows) {
        return new AdminProperties(null, null, 480, 720, new AdminProperties.LoginRateLimit(5, 90, 10_000, 20, 40),
                maxListedProducts, maxReconciliationRows);
    }

    /** Property values that satisfy every non-credential setting except the two caps, for a runner without yml. */
    public static String[] bareValuesWithoutCaps() {
        return new String[] {"ceto.admin.session-idle-timeout-minutes=480",
            "ceto.admin.session-absolute-timeout-minutes=720",
            "ceto.admin.login-rate-limit.burst=5",
            "ceto.admin.login-rate-limit.refill-interval-seconds=90",
            "ceto.admin.login-rate-limit.max-tracked-clients=10000",
            "ceto.admin.login-rate-limit.overflow-burst=20",
            "ceto.admin.login-rate-limit.overflow-refill-per-hour=40"};
    }
}
