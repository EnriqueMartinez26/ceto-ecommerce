package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import adminprobe.AdminProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The probe is reachable for an authenticated principal. This is the control that makes the anonymous matrix
 * ({@link AdminPathVariantsIT}) meaningful: the 401, 403 and 404 answers there are not caused by a probe that nobody
 * can reach.
 */
class AdminProbeAccessIT extends AbstractAdminProbeHttpIT {

    @Test
    void anAuthenticatedPrincipalReachesTheProbeWithGet() throws Exception {
        MockHttpServletResponse response = send(get("/api/admin/probe").with(user("admin")));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(AdminProbeController.SENTINEL);
    }

    @Test
    void anAuthenticatedPrincipalReachesTheProbeWithPostAndCsrf() throws Exception {
        MockHttpServletResponse response = send(post("/api/admin/probe/7").with(user("admin")).with(csrf()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(AdminProbeController.SENTINEL);
    }

    @Test
    void anAuthenticatedPostWithoutCsrfIsStillRefused() throws Exception {
        MockHttpServletResponse response = send(post("/api/admin/probe").with(user("admin")));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).doesNotContain(AdminProbeController.SENTINEL);
    }
}
