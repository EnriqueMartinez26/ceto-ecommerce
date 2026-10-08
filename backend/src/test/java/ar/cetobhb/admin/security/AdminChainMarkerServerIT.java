package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import adminprobe.AdminProbeTestConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The chain marker on a real Tomcat (review fix batch 5, item 7). A client only sees the response, so the marker
 * filter also stamps a header: public responses never carry it, admin responses always do.
 */
class AdminChainMarkerServerIT extends AbstractAdminProbeServerIT {

    private static boolean marked(Raw reply) {
        return reply.headers().containsKey(AdminProbeTestConfig.CHAIN_HEADER.toLowerCase());
    }

    @Test
    void publicResponsesNeverCarryTheMarker() throws Exception {
        for (Raw reply : List.of(raw("GET", "/api/store/products", null, null),
                raw("GET", "/api/store/products/999999", null, null), raw("GET", "/api/store/products;x=1", null, null),
                raw("POST", "/api/checkout/orders", "00000000-0000-4000-8000-000000000000", "{}"),
                raw("GET", "/api/anything-else", null, null))) {
            assertThat(marked(reply)).as("status " + reply.status()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void adminResponsesAlwaysCarryTheMarker(String method) throws Exception {
        for (String path : List.of("/api/admin/probe", "/api/admin/probe/7", "/api/admin/unmapped", "/api/admin",
                "/api/admin/")) {
            Raw reply = raw(method, path, null, method.equals("GET") ? null : "{}");

            assertThat(marked(reply)).as(method + " " + path).isTrue();
        }
    }
}
