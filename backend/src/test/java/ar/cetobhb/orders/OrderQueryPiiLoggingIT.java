package ar.cetobhb.orders;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderListQuery;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.OrderQueryService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin order read paths never write buyer data or the searched text to the log (AOR-02, AOR-03). The sentinels are unique strings: if any of them shows up in the captured output, a read path
 * logged a name, a phone, an address or the search box content.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderQueryPiiLoggingIT extends AbstractPostgresIT {

    private static final String NAME = "SentinelBuyerNameQ";
    private static final String PHONE = "+5493515557777";
    private static final String ADDRESS = "SentinelStreetQ 7777";
    private static final String UNPARSEABLE_QUERY = "SentinelSearchTextQ";
    private static final String PHONE_QUERY = "351 555-7777";

    @Autowired
    private OrderQueryService query;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listDetailAndEverySearchShapeLeaveNoBuyerDataOrSearchTextInTheLog(CapturedOutput output) {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 10);
        long id = placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(NAME, PHONE, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 1)))).orderId();
        String number = jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class, id);
        String lowerNumber = number.toLowerCase(Locale.ROOT);

        assertThat(query.list(new OrderListQuery(null, null, 20, null)).rows()).hasSize(1);
        assertThat(query.list(new OrderListQuery(null, PHONE_QUERY, 20, null)).rows()).hasSize(1);
        assertThat(query.list(new OrderListQuery(null, lowerNumber, 20, null)).rows()).hasSize(1);
        assertThat(query.list(new OrderListQuery(null, UNPARSEABLE_QUERY, 20, null)).rows()).isEmpty();
        assertThat(query.detail(id).buyerName()).isEqualTo(NAME);

        assertThat(output.getAll())
                .doesNotContain(NAME)
                .doesNotContain(PHONE)
                .doesNotContain("5557777")
                .doesNotContain(ADDRESS)
                .doesNotContain(PHONE_QUERY)
                .doesNotContain(UNPARSEABLE_QUERY)
                .doesNotContain(lowerNumber);
    }
}
