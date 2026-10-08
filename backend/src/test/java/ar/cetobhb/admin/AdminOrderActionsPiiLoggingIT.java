package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.service.AdminOrderService;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin order action paths never write buyer data to the log (AOR-04, AOR-05): not on success (pay, dispatch,
 * deliver, cancel), not on an illegal transition and not when the audit insert fails and everything rolls back. The
 * sentinels are unique strings: if one shows up in the captured output, an action path logged a name, a phone or an
 * address.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminOrderActionsPiiLoggingIT extends AbstractPostgresIT {

    private static final String NAME = "SentinelActionNameQ";
    private static final String PHONE = "+5493515558888";
    private static final String ADDRESS = "SentinelActionStreetQ 8888";

    @Autowired
    private AdminOrderService admin;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private long order(long base, String phone) {
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(NAME, phone, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 1)))).orderId();
    }

    @Test
    void everyActionPathLeavesNoBuyerDataInTheLog(CapturedOutput output) {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 20);
        long delivered = order(base, PHONE);
        long cancelled = order(base, "+5493515558889");
        long failing = order(base, "+5493515558890");

        assertThat(admin.pay(delivered).buyerName()).isEqualTo(NAME);
        admin.dispatch(delivered);
        admin.deliver(delivered);
        admin.cancel(cancelled);
        assertThatThrownBy(() -> admin.pay(delivered)).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> admin.cancel(delivered)).isInstanceOf(IllegalOrderTransitionException.class);
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_audit_pii_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'audit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_audit_pii_for_test BEFORE INSERT ON admin_audit_log FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_audit_pii_for_test()");
            assertThatThrownBy(() -> admin.pay(failing)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_audit_pii_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_audit_pii_for_test()");
        }

        assertThat(output.getAll())
                .doesNotContain(NAME)
                .doesNotContain(PHONE)
                .doesNotContain("5558888")
                .doesNotContain("5558889")
                .doesNotContain("5558890")
                .doesNotContain(ADDRESS);
    }
}
