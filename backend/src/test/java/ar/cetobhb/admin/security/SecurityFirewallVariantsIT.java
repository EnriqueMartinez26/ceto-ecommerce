package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import adminprobe.AdminProbeController;
import ar.cetobhb.support.ContractAssertions;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A request the Security firewall refuses (a matrix parameter, an empty or dot segment, an encoded slash) is a 400
 * {@code MALFORMED_REQUEST} problem with the request id and the hardening headers, never a bare body and never a 5xx
 * (design AD-A12, REQ-HRD-08, verification U-1, U-2). Real Tomcat, because the container normalises paths before the
 * firewall sees them.
 */
class SecurityFirewallVariantsIT extends AbstractAdminProbeServerIT {

    static Stream<String> rejectedSpellings() {
        return Stream.of("/api/admin;x=1/orders", "/api/admin//orders", "/api/admin/./orders",
                "/api/admin/orders;x=1", "/api;x=1/admin/orders", "/api//admin/orders", "/api/./admin/orders",
                "/api/store/../admin/orders");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedSpellings")
    void aRejectedSpellingIsA400ProblemWithTheRequestIdAndTheHardeningHeaders(String path) throws Exception {
        for (String method : List.of("GET", "POST")) {
            Raw reply = raw(method, path, null, method.equals("GET") ? null : "{}");

            assertThat(reply.status()).as("%s %s", method, path).isEqualTo(400);
            assertThat(reply.headers().get("content-type").get(0)).startsWith("application/problem+json");
            ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.headers().get("x-request-id").get(0),
                    "admin-error-rejected-request.json", null);
            assertThat(reply.body()).as("the refused spelling is not echoed").doesNotContain("x=1")
                    .doesNotContain("admin");
            assertThat(reply.headers().get("x-content-type-options")).containsExactly("nosniff");
            assertThat(reply.headers().get("referrer-policy")).containsExactly("no-referrer");
            assertThat(reply.headers().get("content-security-policy"))
                    .containsExactly("default-src 'none'; frame-ancestors 'none'");
            assertThat(reply.headers().get("cache-control")).containsExactly("no-store");
            assertThat(reply.headers()).doesNotContainKeys("access-control-allow-origin", "strict-transport-security",
                    "set-cookie");
            assertThat(reply.body()).doesNotContain(AdminProbeController.SENTINEL);
        }
    }

    /**
     * Tomcat itself refuses an encoded slash, an encoded backslash and an encoded NUL before any filter runs (the
     * documented "errors before the filter chain": no problem body, no hardening headers). Pinned so that it stays a
     * 4xx that never reaches a handler; the Security rejection handler above is for what Tomcat lets through.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/admin%2Forders", "/api/admin%5Corders", "/api/admin/orders%00"})
    void anEncodedSlashBackslashOrNulIsRefusedByTheContainerBeforeAnyFilter(String path) throws Exception {
        Raw reply = raw("GET", path, null, null);

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).doesNotContain(AdminProbeController.SENTINEL);
    }

    @Test
    void aVeryLongAnonymousAdminPathIsA401WhoseInstanceIsTheFixedAdminRoot() throws Exception {
        String longPath = "/api/admin/" + "a".repeat(2000);

        Raw reply = raw("GET", longPath, null, null);

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.headers().get("x-request-id").get(0),
                "admin-error-unauthenticated.json", "/api/admin");
        assertThat(reply.body()).doesNotContain("aaaaaaaaaa");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/API/admin/orders", "/api/ADMIN/orders", "/Api/admin/orders"})
    void aDifferentCaseNeverEntersSecurityAndIsTheStandard404(String path) throws Exception {
        Raw reply = raw("GET", path, null, null);

        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.body()).contains("\"code\":\"NOT_FOUND\"");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/admin/orders/", "/api/admin/orders/1", "/api/admin/session", "/api/admin/login"})
    void aWellFormedAnonymousAdminPathIsA401EvenWhenNothingIsMappedThere(String path) throws Exception {
        Raw reply = raw("GET", path, null, null);

        assertThat(reply.status()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(reply.body(), reply.headers().get("x-request-id").get(0),
                "admin-error-unauthenticated.json", path);
    }
}
