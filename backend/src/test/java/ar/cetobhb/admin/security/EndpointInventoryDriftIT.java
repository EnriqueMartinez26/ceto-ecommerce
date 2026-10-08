package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import adminprobe.AdminProbeController;
import ar.cetobhb.support.ContractAssertions;
import ar.cetobhb.support.EndpointInventory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The inventory drift fixture (REQ-HRD-08): a context with one more admin handler than the allowlist knows. The
 * test-only probe plays the part of a new {@code @GetMapping("/api/admin/foo")}. It proves two things the real
 * application cannot yet, because it has no admin controller: the inventory reports a route nobody listed, and the
 * generated anonymous call gets the 401 golden for a mapped route.
 */
class EndpointInventoryDriftIT extends AbstractAdminProbeHttpIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void aMappedAdminRouteThatIsNotInTheAllowlistIsReported() {
        assertThat(EndpointInventory.unexpected(context)).containsExactlyInAnyOrder(
                "GET /api/admin/probe", "POST /api/admin/probe", "PUT /api/admin/probe", "PATCH /api/admin/probe",
                "DELETE /api/admin/probe", "GET /api/admin/probe/{x}", "POST /api/admin/probe/{x}",
                "PUT /api/admin/probe/{x}", "PATCH /api/admin/probe/{x}", "DELETE /api/admin/probe/{x}");
    }

    @Test
    void everyGeneratedAnonymousCallToAMappedNonPublicRouteIsThe401Golden() throws Exception {
        List<EndpointInventory.Mapped> routes = EndpointInventory.nonPublicRoutes(context);
        assertThat(routes).as("the fixture maps routes").hasSize(10);

        for (EndpointInventory.Mapped route : routes) {
            for (MockHttpServletRequestBuilder call : EndpointInventory.anonymousCalls(route)) {
                MockHttpServletResponse response = send(call);

                assertThat(response.getStatus()).as(route.route()).isEqualTo(401);
                assertThat(response.getContentAsString()).doesNotContain(AdminProbeController.SENTINEL);
                ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-unauthenticated.json",
                        EndpointInventory.concretePath(route));
            }
        }
    }

    @Test
    void anUnmappedAdminPathIsTheSame401() throws Exception {
        MockHttpServletResponse response = send(get("/api/admin/unknown"));

        assertThat(response.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-unauthenticated.json",
                "/api/admin/unknown");
    }
}
