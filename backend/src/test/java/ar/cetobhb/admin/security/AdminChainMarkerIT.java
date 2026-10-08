package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import adminprobe.AdminProbeTestConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Deterministic "entered the admin chain" marker (review fix batch 5, item 7): a test-only filter, first in the admin
 * chain, stamps a request attribute (and a response header). Public requests never carry it and admin requests always
 * do, so the scoping tests no longer depend on an attribute that some other filter happens to leave behind. The
 * {@code _csrf} attribute assertions of {@link SecurityFilterScopeIT} stay as a second, independent witness.
 */
class AdminChainMarkerIT extends AbstractAdminProbeHttpIT {

    private static boolean marked(MvcResult result) {
        return Boolean.TRUE.equals(result.getRequest().getAttribute(AdminProbeTestConfig.CHAIN_ATTRIBUTE));
    }

    @Test
    void publicRequestsNeverCarryTheMarker() throws Exception {
        List<MvcResult> results = List.of(sendForResult(get("/api/store/products")),
                sendForResult(get("/api/store/products/999999")),
                sendForResult(post("/api/checkout/orders").contentType("application/json").content("{}")),
                sendForResult(get("/api/store/products;x=1")), sendForResult(get("/api/anything-else")));

        for (MvcResult result : results) {
            assertThat(marked(result)).as(result.getRequest().getRequestURI()).isFalse();
            assertThat(result.getResponse().getHeader(AdminProbeTestConfig.CHAIN_HEADER)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void adminRequestsAlwaysCarryTheMarker(String method) throws Exception {
        for (String path : List.of("/api/admin/probe", "/api/admin/probe/7", "/api/admin/unmapped", "/api/admin",
                "/api/admin/")) {
            MvcResult result = sendForResult(request(HttpMethod.valueOf(method), path));

            assertThat(marked(result)).as(method + " " + path).isTrue();
            assertThat(result.getResponse().getHeader(AdminProbeTestConfig.CHAIN_HEADER)).isEqualTo("true");
        }
    }

    @Test
    void anAuthenticatedAdminRequestCarriesTheMarkerToo() throws Exception {
        MvcResult result = sendForResult(get("/api/admin/probe").with(user("admin")));
        MockHttpServletResponse response = result.getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(marked(result)).isTrue();
    }
}
