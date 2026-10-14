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
 * The stock paths never write buyer data or a rejected request value to the log and never put them in an error body
 * (AUTH-12, AUD-05), with the application, web and Hibernate loggers on DEBUG and on TRACE: the view, the adjustment, the
 * conflicts, the 404, the bodies that carry a {@code note} or an unknown reason, and the reconciliation. Afterwards every
 * text column of {@code admin_audit_log} is scanned for the sentinels: the log accepts only closed sets.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminStockPiiServerIT extends AbstractAdminServerIT {

    private static final String NAME = "SentinelStockNameK";
    private static final String PHONE = "+5493515556666";
    private static final String ADDRESS = "SentinelStockStreetK 6666";
    private static final String NOTE = "SentinelNoteTextK";
    private static final String REASON = "SentinelReasonTextK";
    private static final String[] CATEGORIES = {"ar.cetobhb", "org.springframework.web", "org.hibernate"};
    private static final String JSON = "application/json";

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

    @BeforeEach
    void debugLevels() {
        configured.clear();
        for (String category : CATEGORIES) {
            configured.add(loggingSystem.getLoggerConfiguration(category).getConfiguredLevel());
            loggingSystem.setLogLevel(category, LogLevel.DEBUG);
        }
    }

    @AfterEach
    void restoreLevels() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            loggingSystem.setLogLevel(CATEGORIES[i], configured.get(i));
        }
    }

    private void silencedChildrenKeepTheirLevel() {
        PiiLogLevelGuard.SHIPPED_LEVELS.forEach((category, level) -> {
            assertThat(loggingSystem.getLoggerConfiguration(category).getConfiguredLevel()).as(category)
                    .isEqualTo(LogLevel.valueOf(level));
            assertThat(org.slf4j.LoggerFactory.getLogger(category).isTraceEnabled()).as(category).isFalse();
        });
    }

    @Test
    void stockPathsLeaveNoSentinelInTheLogOrInAnyBodyAtDebug(CapturedOutput output) throws Exception {
        flows(output, false);
    }

    @Test
    void theSameStockPathsWithTheParentsAtTraceLeaveNoSentinelEither(CapturedOutput output) throws Exception {
        for (String category : CATEGORIES) {
            loggingSystem.setLogLevel(category, LogLevel.TRACE);
        }
        flows(output, true);
    }

    private AdminClient.Reply adjust(AdminClient client, String productId, String body) throws Exception {
        return client.send("POST", "/api/admin/stock/" + productId + "/adjust", JSON, body, client.csrfHeader());
    }

    private void flows(CapturedOutput output, boolean trace) throws Exception {
        silencedChildrenKeepTheirLevel();
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 10);
        long combo = catalog.createCombo(product("Combo x2", "180.00"), base, 2).id();
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(NAME, PHONE, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 8))));
        AdminClient client = loggedInClient();

        List<AdminClient.Reply> successes = List.of(client.get("/api/admin/stock"),
                client.get("/api/admin/inventory/reconciliation"),
                adjust(client, String.valueOf(base), "{\"delta\":5,\"reason\":\"RESTOCK\"}"));
        List<AdminClient.Reply> errors = List.of(
                adjust(client, String.valueOf(base), "{\"delta\":-9999,\"reason\":\"OTHER\"}"),
                adjust(client, String.valueOf(combo), "{\"delta\":1,\"reason\":\"RESTOCK\"}"),
                adjust(client, "987654", "{\"delta\":1,\"reason\":\"RESTOCK\"}"),
                adjust(client, String.valueOf(base), "{\"delta\":1,\"reason\":\"RESTOCK\",\"note\":\"" + NOTE + "\"}"),
                adjust(client, String.valueOf(base), "{\"delta\":1,\"reason\":\"" + REASON + "\"}"),
                adjust(client, String.valueOf(base), "{\"delta\":\"" + NOTE + "\",\"reason\":\"RESTOCK\"}"),
                adjust(client, "notanumber", "{\"delta\":1,\"reason\":\"RESTOCK\"}"));

        assertThat(successes).extracting(AdminClient.Reply::status).containsOnly(200);
        assertThat(errors).extracting(AdminClient.Reply::status).containsExactly(409, 409, 404, 400, 400, 400, 400);
        for (AdminClient.Reply reply : successes) {
            assertThat(reply.body()).doesNotContain(NAME).doesNotContain("6666").doesNotContain(ADDRESS);
        }
        for (AdminClient.Reply error : errors) {
            assertThat(error.body()).doesNotContain(NAME).doesNotContain("6666").doesNotContain(ADDRESS)
                    .doesNotContain(NOTE).doesNotContain(REASON).doesNotContain("Sentinel");
        }
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory_stock WHERE product_id = ?", Integer.class, base))
                .as("only the one valid adjustment applied").isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class)).isEqualTo(1);
        List<String> textColumns = jdbc.queryForList(
                "SELECT action || '|' || entity_type || '|' || coalesce(reason, '') FROM admin_audit_log", String.class);
        assertThat(textColumns).allSatisfy(row -> assertThat(row).doesNotContain("Sentinel").doesNotContain(NAME)
                .doesNotContain("6666"));

        assertThat(output.getAll()).as("positive control: the request logs are in the capture")
                .contains("MALFORMED_REQUEST");
        if (trace) {
            assertThat(output.getAll()).as("positive control: TRACE lines of the parents are in the capture")
                    .contains("TRACE");
        }
        assertThat(output.getAll()).doesNotContain(NAME).doesNotContain(PHONE).doesNotContain("6666")
                .doesNotContain(ADDRESS).doesNotContain(NOTE).doesNotContain(REASON).doesNotContain("SentinelStock");
        silencedChildrenKeepTheirLevel();
    }
}
