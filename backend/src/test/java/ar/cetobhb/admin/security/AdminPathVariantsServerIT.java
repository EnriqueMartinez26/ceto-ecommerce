package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import adminprobe.AdminProbeController;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The path-variant matrix of {@link AdminPathVariantsIT} against a real Tomcat (review fix batch 5, item 1). The
 * container decodes and normalises the path before the servlet filter mapping and Security see it, so its answers can
 * differ from MockMvc's; each column is pinned on its own.
 */
class AdminPathVariantsServerIT extends AbstractAdminProbeServerIT {

    /** One spelling of the probe path with the expected anonymous status per method. */
    record Variant(String path, int get, int post) {
        @Override
        public String toString() {
            return path + " [GET " + get + ", POST " + post + "]";
        }
    }

    static Stream<Variant> variants() {
        // 401/403: the admin chain matched and refused (GET: not authenticated; POST: CSRF runs before authorization).
        // 400: Tomcat mapped the request to /api/admin/* and Security's firewall rejected the raw spelling (matrix
        // parameter, empty segment, "." or ".." segment, encoded slash or backslash) before any handler lookup.
        // 404: only the case variants: the servlet filter mapping is case-sensitive, so the request never enters
        // Security and maps to no handler.
        return List.of(
                new Variant("/api/admin/probe", 401, 403),
                new Variant("/api/admin/probe/x", 401, 403),
                new Variant("/api/admin/probe/", 401, 403),
                new Variant("/api/admin", 401, 403),
                new Variant("/api/admin/", 401, 403),
                new Variant("/api;x=1/admin/probe", 400, 400),
                new Variant("/api/admin;x=1/probe", 400, 400),
                new Variant("/api/admin/probe;x=1", 400, 400),
                new Variant("/api//admin/probe", 400, 400),
                new Variant("/api/./admin/probe", 400, 400),
                new Variant("/api/store/../admin/probe", 400, 400),
                // %61 is "a": the decoded path IS /api/admin/probe, so it is treated as exactly that.
                new Variant("/api/%61dmin/probe", 401, 403),
                new Variant("/api/admin%2Fprobe", 400, 400),
                new Variant("/api/admin%5Cprobe", 400, 400),
                new Variant("/API/admin/probe", 404, 404),
                new Variant("/api/ADMIN/probe", 404, 404)).stream();
    }

    private void assertNeverReachesTheProbe(String method, Variant variant, int expectedStatus) throws Exception {
        Raw reply = raw(method, variant.path(), null, method.equals("GET") ? null : "{}");
        String where = method + " " + variant.path();

        assertThat(reply.body()).as(where + " body").doesNotContain(AdminProbeController.SENTINEL);
        assertThat(reply.status()).as(where + " is not 2xx").matches(status -> status < 200 || status > 299);
        assertThat(reply.headers().getOrDefault("set-cookie", List.of())).as(where + " cookies")
                .noneMatch(cookie -> cookie.contains("JSESSIONID"));
        assertThat(reply.status()).as(where + " status").isEqualTo(expectedStatus);
    }

    @ParameterizedTest(name = "GET {0}")
    @MethodSource("variants")
    void anonymousGetNeverReachesTheProbe(Variant variant) throws Exception {
        assertNeverReachesTheProbe("GET", variant, variant.get());
    }

    @ParameterizedTest(name = "POST {0}")
    @MethodSource("variants")
    void anonymousPostNeverReachesTheProbe(Variant variant) throws Exception {
        assertNeverReachesTheProbe("POST", variant, variant.post());
    }
}
