package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import adminprobe.AdminProbeController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The access guard is registered in the real application and protects the real handler mapping on its own, with the
 * Security filter taken out of the picture: a MockMvc built from the application context WITHOUT filters sends the
 * request straight to the dispatcher, which is what a divergence between the servlet mapping and Spring MVC would
 * look like. The probe handler's sentinel proves whether the request reached it.
 */
class AdminAccessGuardIT extends AbstractAdminProbeHttpIT {

    @Autowired
    private WebApplicationContext context;

    private MockMvc withoutFilters;

    @BeforeEach
    void buildWithoutAnyFilter() {
        withoutFilters = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void withNoSecurityFilterAnAnonymousRequestStillNeverReachesTheHandler() throws Exception {
        for (var request : java.util.List.of(get("/api/admin/probe"), post("/api/admin/probe"),
                get("/api/admin/probe/42"))) {
            MockHttpServletResponse response = withoutFilters.perform(request).andReturn().getResponse();

            assertThat(response.getStatus()).as(request.toString()).isEqualTo(401);
            assertThat(response.getContentAsString()).doesNotContain(AdminProbeController.SENTINEL)
                    .contains("\"code\":\"UNAUTHENTICATED\"");
        }
    }

    @Test
    void withAPrincipalTheHandlerIsReached() throws Exception {
        MockHttpServletResponse response = withoutFilters
                .perform(get("/api/admin/probe").principal(() -> "admin")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains(AdminProbeController.SENTINEL);
    }

    @Test
    void thePublicRoutesAreNotGuarded() throws Exception {
        MockHttpServletResponse response = withoutFilters.perform(get("/api/store/products")).andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
