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
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalog paths never write buyer data, product names or a rejected request value to the log and never put the
 * rejected value in an error body (AUTH-12, AUD-05), with the application, web and Hibernate loggers on DEBUG and on
 * TRACE: create (base and combo), list, edit, stale edit, activation, and the rejected names, prices and unknown
 * properties that carry a sentinel. The body of a success carries the product name by design. Afterwards every text
 * column of {@code admin_audit_log} is scanned for the sentinels.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminProductPiiServerIT extends AbstractAdminServerIT {

    private static final String BUYER = "SentinelCatalogBuyerR";
    private static final String PHONE = "+5493515558888";
    private static final String ADDRESS = "SentinelCatalogStreetR 8888";
    private static final String NAME = "SentinelProductNameR";
    private static final String BAD_NAME = "SentinelBadNameR";
    private static final String BAD_PRICE = "SentinelBadPriceR";
    private static final String BAD_PROPERTY = "SentinelBadPropertyR";
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

    @Autowired
    private JsonMapper json;

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
    void catalogPathsLeaveNoSentinelInTheLogOrInAnyErrorBodyAtDebug(CapturedOutput output) throws Exception {
        flows(output, false);
    }

    @Test
    void theSameCatalogPathsWithTheParentsAtTraceLeaveNoSentinelEither(CapturedOutput output) throws Exception {
        for (String category : CATEGORIES) {
            loggingSystem.setLogLevel(category, LogLevel.TRACE);
        }
        flows(output, true);
    }

    private AdminClient.Reply send(AdminClient client, String method, String path, String body) throws Exception {
        return client.send(method, path, JSON, body, client.csrfHeader());
    }

    private void flows(CapturedOutput output, boolean trace) throws Exception {
        silencedChildrenKeepTheirLevel();
        long seed = catalog.createBase(product("Seed", "10.00")).id();
        inventory.createStock(seed, 10);
        placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(BUYER, PHONE, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(seed, 1))));
        AdminClient client = loggedInClient();

        AdminClient.Reply createdBase = send(client, "POST", "/api/admin/products",
                "{\"type\":\"BASE\",\"name\":\"" + NAME + "\",\"price\":\"100.00\",\"compareAtPrice\":\"150.00\"}");
        long id = json.readTree(createdBase.body()).get("id").asLong();
        List<AdminClient.Reply> successes = List.of(createdBase,
                send(client, "POST", "/api/admin/products", "{\"type\":\"COMBO\",\"name\":\"" + NAME + " combo\","
                        + "\"price\":\"270.00\",\"baseProductId\":" + id + ",\"units\":3}"),
                client.get("/api/admin/products"),
                send(client, "PUT", "/api/admin/products/" + id,
                        "{\"name\":\"" + NAME + " 2\",\"price\":\"120.00\",\"version\":0}"),
                send(client, "POST", "/api/admin/products/" + id + "/deactivate", null),
                send(client, "POST", "/api/admin/products/" + id + "/activate", null));
        List<AdminClient.Reply> errors = List.of(
                send(client, "PUT", "/api/admin/products/" + id,
                        "{\"name\":\"" + BAD_NAME + "\",\"price\":\"1.00\",\"version\":0}"),
                send(client, "POST", "/api/admin/products",
                        "{\"type\":\"BASE\",\"name\":\"" + BAD_NAME + "\\u0007\",\"price\":\"10.00\"}"),
                send(client, "POST", "/api/admin/products",
                        "{\"type\":\"BASE\",\"name\":\"" + BAD_NAME + "\",\"price\":\"" + BAD_PRICE + "\"}"),
                send(client, "POST", "/api/admin/products",
                        "{\"type\":\"BASE\",\"name\":\"" + BAD_NAME + "\",\"price\":\"10.00\",\"" + BAD_PROPERTY
                                + "\":1}"),
                send(client, "POST", "/api/admin/products",
                        "{\"type\":\"" + BAD_PROPERTY + "\",\"name\":\"X\",\"price\":\"10.00\"}"),
                send(client, "PUT", "/api/admin/products/" + id,
                        "{\"name\":\"X\",\"price\":\"" + BAD_PRICE + "\",\"version\":1}"),
                send(client, "PUT", "/api/admin/products/987654",
                        "{\"name\":\"" + BAD_NAME + "\",\"price\":\"10.00\",\"version\":0}"),
                send(client, "POST", "/api/admin/products/" + id + "/frobnicate", null));

        assertThat(successes).extracting(AdminClient.Reply::status).containsExactly(201, 201, 200, 200, 200, 200);
        assertThat(errors).extracting(AdminClient.Reply::status).containsExactly(409, 400, 400, 400, 400, 400, 404, 404);
        assertThat(successes.get(0).body()).as("positive control: a success body carries the name").contains(NAME);
        for (AdminClient.Reply reply : successes) {
            assertThat(reply.body()).doesNotContain(BUYER).doesNotContain("8888").doesNotContain(ADDRESS);
        }
        for (AdminClient.Reply error : errors) {
            assertThat(error.body()).doesNotContain(BUYER).doesNotContain("8888").doesNotContain(ADDRESS)
                    .doesNotContain(NAME).doesNotContain(BAD_NAME).doesNotContain(BAD_PRICE)
                    .doesNotContain(BAD_PROPERTY);
        }
        List<String> textColumns = jdbc.queryForList(
                "SELECT action || '|' || entity_type || '|' || coalesce(reason, '') FROM admin_audit_log", String.class);
        assertThat(textColumns).isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain("Sentinel")
                .doesNotContain("8888"));

        assertThat(output.getAll()).as("positive control: the request logs are in the capture")
                .contains("VALIDATION_ERROR");
        if (trace) {
            assertThat(output.getAll()).as("positive control: TRACE lines of the parents are in the capture")
                    .contains("TRACE");
        }
        assertThat(output.getAll()).doesNotContain(BUYER).doesNotContain(PHONE).doesNotContain("8888")
                .doesNotContain(ADDRESS).doesNotContain(NAME).doesNotContain(BAD_NAME).doesNotContain(BAD_PRICE)
                .doesNotContain(BAD_PROPERTY);
        silencedChildrenKeepTheirLevel();
    }
}
