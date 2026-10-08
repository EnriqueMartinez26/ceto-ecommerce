package ar.cetobhb.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ar.cetobhb.support.AbstractHttpIT;
import ar.cetobhb.support.ContractAssertions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code GET /api/admin/session} (REQ-AUTH-13): the bootstrap that tells a client whether it is logged in. Nothing
 * but the flag is in the body. Note: this class calls {@code user()}, so it makes no assertion about server sessions
 * (spring-security-test installs its own context repository in the shared MockMvc filter); session behaviour lives in
 * the real-server ITs.
 */
class AdminSessionEndpointIT extends AbstractHttpIT {

    @Test
    void anAnonymousClientGetsTheAnonymousGoldenAndTheNoStoreHeader() throws Exception {
        MockHttpServletResponse response = send(get("/api/admin/session"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/json");
        ContractAssertions.assertMatchesGolden(response, "admin-session-anonymous.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    /**
     * MockMvc renders the cookie without its SameSite attribute (it rebuilds the header from a servlet Cookie), so the
     * SameSite=Strict assertion lives in the real-server tests ({@code AdminSessionPermitServerIT},
     * {@code AdminAnonymousSessionServerIT}); here Secure, Path and the absence of HttpOnly are pinned.
     */
    @Test
    void theResponseCarriesTheXsrfCookieWithTheDocumentedFlagsAndNeverASessionCookie() throws Exception {
        MockHttpServletResponse response = send(get("/api/admin/session"));

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertThat(cookies).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
        assertThat(cookies).filteredOn(cookie -> cookie.startsWith("__Host-XSRF-TOKEN=")).singleElement().satisfies(cookie ->
                assertThat(cookie).contains("Secure").contains("Path=/").doesNotContain("HttpOnly"));
    }

    @Test
    void anAuthenticatedClientGetsTheAuthenticatedGoldenWithNoUsernameInIt() throws Exception {
        MockHttpServletResponse response = send(get("/api/admin/session").with(user("sentinel-admin-name")));

        assertThat(response.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(response, "admin-session-authenticated.json");
        assertThat(response.getContentAsString()).doesNotContain("sentinel-admin-name");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void theBodyIsExactlyTheFlag() throws Exception {
        MockHttpServletResponse anonymous = send(get("/api/admin/session"));
        MockHttpServletResponse authenticated = send(get("/api/admin/session").with(user("x")));

        assertThat(anonymous.getContentAsString()).isEqualTo("{\"authenticated\":false}");
        assertThat(authenticated.getContentAsString()).isEqualTo("{\"authenticated\":true}");
    }
}
