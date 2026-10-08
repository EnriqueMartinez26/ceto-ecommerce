package ar.cetobhb.common.web;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractServerIT;
import ar.cetobhb.support.PublicSurface;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Real-server twin of {@link PublicSurfaceUnchangedIT} (REQ-AUTH-06, REQ-HRD-09, REQ-HRD-10): over a real socket the
 * public routes keep their header names, set no cookie and need no token, and the storefront request-target variants
 * answer as they did before Spring Security joined the application (a Security firewall in front of them would answer
 * 400 for the {@code ;}, {@code //} and {@code /./} forms).
 */
class PublicSurfaceUnchangedServerIT extends AbstractServerIT {

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

    @Test
    void storefrontReadsKeepTheirHeaderNamesAndSetNoCookie() throws Exception {
        Raw list = raw("GET", "/api/store/products", null, null);
        Raw detail = raw("GET", "/api/store/products/" + productId, null, null);
        Raw missing = raw("GET", "/api/store/products/999999", null, null);

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.applicationHeaderNames()).isEqualTo(PublicSurface.LIST);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.applicationHeaderNames()).isEqualTo(PublicSurface.DETAIL);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.applicationHeaderNames()).isEqualTo(PublicSurface.ERROR_404);
    }

    @Test
    void anAnonymousCheckoutNeedsNoCookieOrTokenAndSetsNone() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = BODY.formatted(productId);

        Raw created = raw("POST", "/api/checkout/orders", key, body);
        Raw replay = raw("POST", "/api/checkout/orders", key, body);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.applicationHeaderNames()).isEqualTo(PublicSurface.CHECKOUT_201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.applicationHeaderNames()).isEqualTo(PublicSurface.CHECKOUT_200_REPLAY);
        assertThat(created.applicationHeaderNames()).doesNotContainAnyElementsOf(PublicSurface.SECURITY_DEFAULTS);
        assertThat(replay.applicationHeaderNames()).doesNotContainAnyElementsOf(PublicSurface.SECURITY_DEFAULTS);
    }

    /** What Tomcat 11 and Spring MVC 7 do with each form of the storefront list, pinned before the dependency existed. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            /api/store/products;x=1             | 200
            /api/store/products;                | 200
            /api//store/products                | 404
            /api/store/./products               | 404
            /api/store/%70roducts               | 200
            /api/store/products/                | 404
            /API/store/products                 | 404
            /api/store/PRODUCTS                 | 404
            """)
    void aStorefrontPathVariantKeepsItsAnswer(String variant, int expected) throws Exception {
        Raw response = raw("GET", variant.trim(), null, null);

        assertThat(response.status()).as("variant %s", variant.trim()).isEqualTo(expected);
        assertThat(response.applicationHeaderNames()).doesNotContainAnyElementsOf(PublicSurface.SECURITY_DEFAULTS);
    }
}
