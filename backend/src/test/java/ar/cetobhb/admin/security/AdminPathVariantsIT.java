package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import adminprobe.AdminProbeController;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Path-variant matrix on MockMvc (review fix batch 5, item 1). A test-only handler at {@code /api/admin/probe} answers
 * 200 with a sentinel; every spelling of that path is requested anonymously with GET and POST and none may reach it,
 * produce a 2xx, or open a session. Each status is pinned exactly so a change of behaviour is a visible diff, not a
 * silent drift. The same table runs against a real Tomcat in {@link AdminPathVariantsServerIT}, where the container
 * normalises and decodes paths before Security sees them, so the two columns can differ.
 *
 * <p>A 404 here means the request maps to no handler (the servlet filter mapping or the MVC mapping is case-sensitive
 * and exact), never that the probe was hidden: {@link AdminProbeAccessIT} proves the probe is reachable when
 * authenticated, so these answers are not vacuous.
 */
class AdminPathVariantsIT extends AbstractAdminProbeHttpIT {

    /** One spelling of the probe path with the expected anonymous status per method. */
    record Variant(String path, int get, int post) {
        @Override
        public String toString() {
            return path + " [GET " + get + ", POST " + post + "]";
        }
    }

    static Stream<Variant> variants() {
        // 401/403: the admin chain matched and refused (GET: not authenticated; POST: CSRF runs before authorization).
        // 400: Security's firewall rejected a path spelling it does not allow (matrix parameter or empty segment).
        // 404: the request maps to no handler, so nothing was exposed. Case variants are other (unmapped) paths. MockMvc
        // also does not normalise "/./" or ".." and does not route an encoded backslash through the admin filter mapping
        // (the real server answers 400 for all three, see AdminPathVariantsServerIT).
        return List.of(
                new Variant("/api/admin/probe", 401, 403),
                new Variant("/api/admin/probe/x", 401, 403),
                // Spring MVC no longer matches a trailing slash to the same handler, but the admin chain refuses the request
                // before routing, so the answer is the chain's, not a 404.
                new Variant("/api/admin/probe/", 401, 403),
                // The prefix itself and its trailing-slash form belong to the chain as well (nothing is mapped there).
                new Variant("/api/admin", 401, 403),
                new Variant("/api/admin/", 401, 403),
                new Variant("/api;x=1/admin/probe", 400, 400),
                new Variant("/api/admin;x=1/probe", 400, 400),
                new Variant("/api/admin/probe;x=1", 400, 400),
                new Variant("/api//admin/probe", 400, 400),
                new Variant("/api/./admin/probe", 404, 404),
                new Variant("/api/store/../admin/probe", 404, 404),
                // %61 is "a": the decoded path IS /api/admin/probe, so it is treated as exactly that.
                new Variant("/api/%61dmin/probe", 401, 403),
                new Variant("/api/admin%2Fprobe", 400, 400),
                new Variant("/api/admin%5Cprobe", 404, 404),
                // Paths are case-sensitive for the servlet mapping and for MVC: another case is another (unmapped) path.
                new Variant("/API/admin/probe", 404, 404),
                new Variant("/api/ADMIN/probe", 404, 404)).stream();
    }

    private void assertNeverReachesTheProbe(HttpMethod method, Variant variant, int expectedStatus) throws Exception {
        MvcResult result = sendForResult(request(method, URI.create(variant.path())).content("{}")
                .contentType("application/json"));
        MockHttpServletResponse response = result.getResponse();
        String where = method + " " + variant.path();

        assertThat(response.getContentAsString()).as(where + " body").doesNotContain(AdminProbeController.SENTINEL);
        assertThat(response.getStatus()).as(where + " is not 2xx").matches(status -> status < 200 || status > 299);
        assertThat(response.getHeaders("Set-Cookie")).as(where + " cookies")
                .noneMatch(cookie -> cookie.contains("JSESSIONID"));
        assertThat(result.getRequest().getSession(false)).as(where + " session").isNull();
        assertThat(response.getStatus()).as(where + " status").isEqualTo(expectedStatus);
    }

    @ParameterizedTest(name = "GET {0}")
    @MethodSource("variants")
    void anonymousGetNeverReachesTheProbe(Variant variant) throws Exception {
        assertNeverReachesTheProbe(HttpMethod.GET, variant, variant.get());
    }

    @ParameterizedTest(name = "POST {0}")
    @MethodSource("variants")
    void anonymousPostNeverReachesTheProbe(Variant variant) throws Exception {
        assertNeverReachesTheProbe(HttpMethod.POST, variant, variant.post());
    }
}
