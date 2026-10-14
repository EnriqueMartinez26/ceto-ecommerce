package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.common.config.PiiLogLevelGuard;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractAdminServerIT;
import ar.cetobhb.support.AdminClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The HTTP action paths never write buyer data to the log and never put it in an error body (AOR-08, AUTH-12), with
 * the application, web and Hibernate loggers on DEBUG and then on TRACE (a parent at TRACE must not lift a silenced child): success of all four actions (the bodies of those carry the buyer by design),
 * the 409 of an illegal edge and of a repeated tap, the 404 of an unknown id, the 400 of a bad id, the 403 of a missing
 * token, and the rolled-back action when the audit insert fails.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminOrderActionsPiiServerIT extends AbstractAdminServerIT {

    private static final String NAME = "SentinelActionNameH";
    private static final String ADDRESS = "SentinelActionStreetH 4444";
    private static final String[] CATEGORIES = {"ar.cetobhb", "org.springframework.web", "org.hibernate"};

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoggingSystem loggingSystem;

    private final List<LogLevel> configured = new ArrayList<>();
    private long base;

    @BeforeEach
    void debugLevels() {
        configured.clear();
        for (String category : CATEGORIES) {
            configured.add(loggingSystem.getLoggerConfiguration(category).getConfiguredLevel());
            loggingSystem.setLogLevel(category, LogLevel.DEBUG);
        }
        base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 50);
    }

    @AfterEach
    void restoreLevels() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            loggingSystem.setLogLevel(CATEGORIES[i], configured.get(i));
        }
    }

    private long order(String phone) {
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(NAME, phone, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 1)))).orderId();
    }

    private static String path(long id, String action) {
        return "/api/admin/orders/" + id + "/" + action;
    }

    /** Every silenced child of those parents (PiiLogLevelGuard.SHIPPED_LEVELS) keeps its own explicit level. */
    private void assertSilencedChildrenKeepTheirLevel() {
        PiiLogLevelGuard.SHIPPED_LEVELS.forEach((category, level) -> {
            assertThat(loggingSystem.getLoggerConfiguration(category).getConfiguredLevel()).as(category)
                    .isEqualTo(LogLevel.valueOf(level));
            assertThat(org.slf4j.LoggerFactory.getLogger(category).isTraceEnabled()).as(category + " trace").isFalse();
            assertThat(org.slf4j.LoggerFactory.getLogger(category).isDebugEnabled()).as(category + " debug").isFalse();
        });
    }

    @Test
    void everyActionPathLeavesNoBuyerDataInTheLogOrInAnyErrorBody(CapturedOutput output) throws Exception {
        actionsLeaveNoTrace(output, false);
    }

    /** The same flows with the parents at TRACE: bound SQL values and message bodies print only if a child is lifted. */
    @Test
    void theSameActionsWithTheParentsAtTraceLeaveNoBuyerDataEither(CapturedOutput output) throws Exception {
        for (String category : CATEGORIES) {
            loggingSystem.setLogLevel(category, LogLevel.TRACE);
        }
        actionsLeaveNoTrace(output, true);
    }

    private void actionsLeaveNoTrace(CapturedOutput output, boolean trace) throws Exception {
        assertSilencedChildrenKeepTheirLevel();
        long delivered = order("+5493515554441");
        long cancelled = order("+5493515554442");
        long rolledBack = order("+5493515554443");
        AdminClient client = loggedInClient();

        List<AdminClient.Reply> successes = List.of(
                client.postWithToken(path(delivered, "pay")), client.postWithToken(path(delivered, "dispatch")),
                client.postWithToken(path(delivered, "deliver")), client.postWithToken(path(cancelled, "cancel")));
        List<AdminClient.Reply> errors = new ArrayList<>(List.of(
                client.postWithToken(path(delivered, "pay")), client.postWithToken(path(delivered, "cancel")),
                client.postWithToken(path(cancelled, "dispatch")), client.postWithToken(path(987654, "pay")),
                client.postWithToken(path(0, "cancel")), client.postWithToken("/api/admin/orders/abc/pay"),
                client.send("POST", path(rolledBack, "pay"), null, null, Map.of())));
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_audit_http_for_test() RETURNS trigger AS $$ BEGIN"
                    + " RAISE EXCEPTION 'audit refused' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER fail_audit_http_for_test BEFORE INSERT ON admin_audit_log FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_audit_http_for_test()");
            errors.add(client.postWithToken(path(rolledBack, "pay")));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_audit_http_for_test ON admin_audit_log");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_audit_http_for_test()");
        }

        assertThat(successes).extracting(AdminClient.Reply::status).containsOnly(200);
        assertThat(successes.get(0).body()).as("positive control: a success body carries the buyer").contains(NAME);
        assertThat(errors).extracting(AdminClient.Reply::status)
                .containsExactly(409, 409, 409, 404, 400, 400, 403, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, rolledBack))
                .as("the failed audit rolled the payment back").isEqualTo("PAGO_PENDIENTE");
        for (AdminClient.Reply error : errors) {
            assertThat(error.body()).doesNotContain(NAME).doesNotContain("5554441").doesNotContain("5554442")
                    .doesNotContain("5554443").doesNotContain(ADDRESS).doesNotContain("PAGADO");
        }

        assertThat(output.getAll()).as("positive control: the INFO line of an illegal edge is in the capture")
                .contains("ILLEGAL_TRANSITION");
        if (trace) {
            assertThat(output.getAll()).as("positive control: TRACE lines of the parents are in the capture")
                    .contains("TRACE");
        }
        assertSilencedChildrenKeepTheirLevel();
        assertThat(output.getAll()).doesNotContain(NAME).doesNotContain("5554441").doesNotContain("5554442")
                .doesNotContain("5554443").doesNotContain(ADDRESS).doesNotContain("SentinelAction");
    }
}
