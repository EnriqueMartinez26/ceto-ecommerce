package ar.cetobhb.common.web;

import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractHttpIT;
import ar.cetobhb.support.PublicSurface;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The public API is untouched by Spring Security (REQ-AUTH-06, REQ-HRD-09, REQ-HRD-10): the Security servlet filter is
 * scoped to {@code /api/admin/*}, so storefront and checkout answers keep exactly the header NAMES they had before the
 * dependency existed, never set a cookie and never ask for a CSRF token. The server-side twin is
 * {@code PublicSurfaceUnchangedServerIT}.
 */
class PublicSurfaceUnchangedIT extends AbstractHttpIT {

    private static final String BODY = """
            {"buyer":{"name":"Maria Perez","phone":"+5493515550000","address":"Calle Falsa 123",
                      "zone":"RESTO_DEL_PAIS"},
             "lines":[{"productId":%d,"quantity":3}]}
            """;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    private long productId;

    @BeforeEach
    void seed() {
        productId = catalog.createBase(product("Solid shampoo", "45000.00")).id();
        inventory.createStock(productId, 10);
    }

    private static Set<String> names(MockHttpServletResponse response) {
        Set<String> names = new TreeSet<>();
        response.getHeaderNames().forEach(n -> names.add(n.toLowerCase(java.util.Locale.ROOT)));
        return names;
    }

    private MockHttpServletRequestBuilder checkout(String key) {
        return post("/api/checkout/orders").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content(BODY.formatted(productId).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storefrontListKeepsItsHeaderNames() throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(names(response)).isEqualTo(PublicSurface.LIST);
    }

    @Test
    void storefrontDetailKeepsItsHeaderNames() throws Exception {
        MockHttpServletResponse response = send(get("/api/store/products/" + productId));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(names(response)).isEqualTo(PublicSurface.DETAIL);
    }

    @Test
    void anAnonymousCheckoutWithoutCookieOrTokenIs201ThenReplay200WithTheSameHeaderNames() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse created = send(checkout(key));
        MockHttpServletResponse replay = send(checkout(key));

        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(names(created)).isEqualTo(PublicSurface.CHECKOUT_201);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(names(replay)).isEqualTo(PublicSurface.CHECKOUT_200_REPLAY);
    }

    @Test
    void anErrorKeepsItsHeaderNamesAndItsGolden() throws Exception {
        MockHttpServletResponse response = send(get("/api/checkout/unknown"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(names(response)).isEqualTo(PublicSurface.ERROR_404);
        assertMatchesGolden(response, "error-not-found.json");
    }

    @Test
    void noPublicResponseCarriesASecurityDefaultOrACookie() throws Exception {
        String key = UUID.randomUUID().toString();
        for (MockHttpServletResponse response : new MockHttpServletResponse[] {
                send(get("/api/store/products")), send(get("/api/store/products/" + productId)),
                send(get("/api/store/products/999999")), send(checkout(key)), send(checkout(key)),
                send(get("/api/nothing-here")) }) {
            assertThat(names(response)).doesNotContainAnyElementsOf(PublicSurface.SECURITY_DEFAULTS);
            assertThat(response.getCookies()).isEmpty();
            assertThat(response.getHeaders("X-Content-Type-Options")).hasSize(1);
        }
    }
}
