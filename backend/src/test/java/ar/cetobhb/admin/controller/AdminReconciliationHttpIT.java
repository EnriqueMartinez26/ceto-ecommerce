package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.ContractAssertions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code GET /api/admin/inventory/reconciliation} (AINV-06, AINV-07): clean data gives an empty list; a counter forced
 * off by raw SQL gives exactly that product, and the read changes nothing.
 */
class AdminReconciliationHttpIT extends AbstractAdminHttpIT {

    private static final String PATH = "/api/admin/inventory/reconciliation";

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    private long baseWithAPendingOrder() {
        long id = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(id, 10);
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer("Ana Gomez", "+5493515550101", "Calle Falsa 123", ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(id, 2))));
        return id;
    }

    @Test
    void consistentDataGivesAnEmptyUntruncatedList() throws Exception {
        baseWithAPendingOrder();

        MockHttpServletResponse response = send(adminGet(PATH));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"mismatches\":[],\"truncated\":false}");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void aCounterForcedOffGivesExactlyThatProductMatchesTheGoldenAndChangesNothing() throws Exception {
        long id = baseWithAPendingOrder();
        jdbc.update("UPDATE inventory_stock SET reserved = 5 WHERE product_id = ?", id);

        MockHttpServletResponse response = send(adminGet(PATH));

        assertThat(response.getStatus()).isEqualTo(200);
        ContractAssertions.assertMatchesGolden(response, "admin-reconciliation.json");
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_stock WHERE product_id = ?", Integer.class, id))
                .as("read-only: the wrong counter is reported, never repaired").isEqualTo(5);
        assertThat(auditRows()).isZero();
    }

    @Test
    void aProductWithPendingDemandAndNoStockRowIsListedWithANullReservedCounter() throws Exception {
        long id = baseWithAPendingOrder();
        jdbc.update("DELETE FROM inventory_stock WHERE product_id = ?", id);

        MockHttpServletResponse response = send(adminGet(PATH));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"mismatches\":[{\"productId\":" + id + ",\"reserved\":null,\"expected\":2}],"
                        + "\"truncated\":false}");
    }

    @Test
    void withoutASessionIs401AndThePostMethodIs405() throws Exception {
        MockHttpServletResponse anonymous = send(get(PATH));

        assertThat(anonymous.getStatus()).isEqualTo(401);
        ContractAssertions.assertMatchesGoldenForPath(anonymous, "admin-error-unauthenticated.json", PATH);
        assertThat(send(adminPost(PATH)).getStatus()).isEqualTo(405);
        assertThat(send(post(PATH)).getStatus()).isEqualTo(403);
    }
}
