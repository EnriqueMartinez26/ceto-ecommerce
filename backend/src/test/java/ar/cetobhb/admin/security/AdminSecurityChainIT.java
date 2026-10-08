package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import adminprobe.AdminProbeController;
import ar.cetobhb.support.ContractAssertions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The administration chain answers with problem bodies (REQ-ERR-06, REQ-AUTH-11, REQ-HRD-09). The request id and the
 * hardening headers come from filters that run before Security, so a 401 or a 403 written by Security carries them.
 * A CSRF failure lands in the same 403 as any other denial. The probe handler is the stand-in for an admin route: its
 * sentinel proves whether a request reached it. Session creation is pinned in {@code AdminAnonymousSessionIT}, not here:
 * {@code user()} installs a test security-context repository in the shared filter, which makes any later session
 * assertion in the same context order dependent.
 */
class AdminSecurityChainIT extends AbstractAdminProbeHttpIT {

    private static final String PATH = "/api/admin/probe";

    /** What every response of the administration area carries, whoever wrote it (REQ-HRD-09, REQ-HRD-13). */
    private static void assertHardeningHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("X-Request-Id")).as("X-Request-Id").isNotBlank();
        assertThat(response.getHeaders("X-Content-Type-Options")).as("X-Content-Type-Options").containsExactly("nosniff");
        assertThat(response.getHeaders("Referrer-Policy")).containsExactly("no-referrer");
        assertThat(response.getHeaders("Content-Security-Policy"))
                .containsExactly("default-src 'none'; frame-ancestors 'none'");
        assertThat(response.getHeader("Cache-Control")).as("Cache-Control").contains("no-store");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).as("CORS").isNull();
        assertThat(response.getHeader("Strict-Transport-Security")).as("HSTS is Caddy's job").isNull();
        assertThat(response.getHeaders("Set-Cookie")).as("no session cookie")
                .noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
    }

    @Test
    void anAnonymousRequestIsA401ProblemWithTheGoldenShapeAndTheHardeningHeaders() throws Exception {
        MockHttpServletResponse response = send(get(PATH).header("Origin", "https://evil.example"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-unauthenticated.json", PATH);
        assertHardeningHeaders(response);
        assertThat(response.getContentAsString()).doesNotContain(AdminProbeController.SENTINEL);
    }

    @Test
    void anAuthenticatedRequestWithoutTheCsrfTokenIsA403ProblemAndDoesNotReachTheHandler() throws Exception {
        MockHttpServletResponse response = send(post(PATH).with(user("admin")));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-forbidden.json", PATH);
        assertHardeningHeaders(response);
        assertThat(response.getContentAsString()).doesNotContain(AdminProbeController.SENTINEL);
    }

    @Test
    void aGarbageCsrfTokenIsTheSame403() throws Exception {
        MockHttpServletResponse response = send(post(PATH).with(user("admin")).header("X-XSRF-TOKEN", "garbage"));

        assertThat(response.getStatus()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-forbidden.json", PATH);
        assertThat(response.getContentAsString()).doesNotContain("garbage").doesNotContain(AdminProbeController.SENTINEL);
    }

    @Test
    void anAnonymousPostWithoutTheTokenIsAlsoA403BecauseTheCsrfCheckComesFirst() throws Exception {
        MockHttpServletResponse response = send(post(PATH));

        assertThat(response.getStatus()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-forbidden.json", PATH);
        assertHardeningHeaders(response);
    }

    @Test
    void anAnonymousPostWithAValidTokenIsA401() throws Exception {
        MockHttpServletResponse response = send(post(PATH).with(csrf()));

        assertThat(response.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-unauthenticated.json", PATH);
    }

    @Test
    void anAuthenticatedRequestWithAValidTokenReachesTheHandlerAndKeepsTheHardeningHeaders() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get(PATH).with(user("admin")),
                post(PATH).with(user("admin")).with(csrf()))) {
            MockHttpServletResponse response = send(request);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains(AdminProbeController.SENTINEL);
            assertHardeningHeaders(response);
            assertThat(response.getHeader("X-Frame-Options")).as("Security default kept on admin").isEqualTo("DENY");
            assertThat(response.getHeader("Cache-Control")).as("Security no-cache family").contains("no-cache", "no-store");
        }
    }

    @Test
    void securityNeverAddsStrictTransportSecurityEvenOnASecureRequest() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get(PATH).secure(true), get(PATH).secure(true).with(user("admin")),
                post(PATH).secure(true).with(user("admin")))) {
            MockHttpServletResponse response = send(request);

            assertThat(response.getHeader("Strict-Transport-Security")).isNull();
        }
    }

    @Test
    void theProblemBodyNeverEchoesWhatTheClientSent() throws Exception {
        MockHttpServletResponse response = send(post(PATH + "?q=sentinel-query").with(user("admin"))
                .header("Authorization", "Bearer sentinel-token").header("X-XSRF-TOKEN", "sentinel-csrf")
                .content("{\"password\":\"sentinel-body\"}").contentType("application/json"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain("sentinel");
        assertThat(response.getHeaderNames()).allSatisfy(name ->
                assertThat(response.getHeaders(name)).noneMatch(value -> value.contains("sentinel")));
    }
}
