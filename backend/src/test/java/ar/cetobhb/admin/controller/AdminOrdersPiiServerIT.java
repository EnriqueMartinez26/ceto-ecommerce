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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

/**
 * The HTTP read paths of the order list and detail never write buyer data or the search text to the log (AOR-08,
 * AUTH-12), with the application, web and Hibernate loggers on DEBUG and then on TRACE (a PARENT category at TRACE must not
 * lift a silenced child: the child keeps its own explicit level). The detail BODY carries the buyer's data by design; every
 * error body and every list row must not carry the address, and no error body repeats the search text, the status
 * or the id that was sent.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminOrdersPiiServerIT extends AbstractAdminServerIT {

    private static final String NAME = "SentinelReadNameZ";
    private static final String PHONE = "+5493515557777";
    private static final String ADDRESS = "SentinelReadStreetZ 7777";
    private static final String SEARCH_SENTINEL = "SentinelSearchTextZ";
    private static final String[] CATEGORIES = {"ar.cetobhb", "org.springframework.web", "org.hibernate"};

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private OrderPlacementService placement;

    @Autowired
    private LoggingSystem loggingSystem;

    /** Every silenced child of those parents (PiiLogLevelGuard.SHIPPED_LEVELS) keeps its own explicit level. */
    private void assertSilencedChildrenKeepTheirLevel() {
        PiiLogLevelGuard.SHIPPED_LEVELS.forEach((category, level) -> {
            assertThat(loggingSystem.getLoggerConfiguration(category).getConfiguredLevel()).as(category)
                    .isEqualTo(LogLevel.valueOf(level));
            assertThat(org.slf4j.LoggerFactory.getLogger(category).isTraceEnabled()).as(category + " trace").isFalse();
            assertThat(org.slf4j.LoggerFactory.getLogger(category).isDebugEnabled()).as(category + " debug").isFalse();
        });
    }

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

    /**
     * The request line with the query string (DispatcherServlet) and the rejected value of a parameter that cannot be
     * converted (HandlerMethod, ExceptionHandlerExceptionResolver) are DEBUG lines: the shipped level is set on each
     * category itself, so raising org.springframework.web does not print the search text.
     */
    @Test
    void theCategoriesThatEchoARequestValueShipSilencedOnTheirOwn() {
        assertThat(loggingSystem.getLoggerConfiguration("org.springframework.web.servlet.DispatcherServlet")
                .getConfiguredLevel()).as("DispatcherServlet, configured not inherited").isEqualTo(LogLevel.INFO);
        assertThat(loggingSystem.getLoggerConfiguration("org.springframework.web.method.HandlerMethod")
                .getConfiguredLevel()).as("HandlerMethod").isEqualTo(LogLevel.OFF);
        assertThat(loggingSystem.getLoggerConfiguration(
                "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver")
                .getConfiguredLevel()).as("ExceptionHandlerExceptionResolver").isEqualTo(LogLevel.OFF);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    void listAndDetailReadsLeaveNoBuyerDataOrSearchTextInTheLogOrInAnyErrorBody(CapturedOutput output)
            throws Exception {
        readsLeaveNoTrace(output, false);
    }

    /** The same flows with the parents at TRACE: bound SQL values and message bodies print only if a child is lifted. */
    @Test
    void theSameReadsWithTheParentsAtTraceLeaveNoBuyerDataEither(CapturedOutput output) throws Exception {
        for (String category : CATEGORIES) {
            loggingSystem.setLogLevel(category, LogLevel.TRACE);
        }
        readsLeaveNoTrace(output, true);
    }

    private void readsLeaveNoTrace(CapturedOutput output, boolean trace) throws Exception {
        assertSilencedChildrenKeepTheirLevel();
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 10);
        long id = placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(NAME, PHONE, ADDRESS, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, 1)))).orderId();
        AdminClient client = loggedInClient();

        List<AdminClient.Reply> successes = List.of(
                client.get("/api/admin/orders"),
                client.get("/api/admin/orders?q=" + enc(PHONE)),
                client.get("/api/admin/orders?q=" + enc("351 555 7777")),
                client.get("/api/admin/orders?q=CETO-" + String.format("%06d", id)),
                client.get("/api/admin/orders?q=" + enc(SEARCH_SENTINEL)),
                client.get("/api/admin/orders/" + id));
        List<AdminClient.Reply> errors = List.of(
                client.get("/api/admin/orders?q=" + enc(SEARCH_SENTINEL.repeat(5))),
                client.get("/api/admin/orders?status=" + SEARCH_SENTINEL),
                client.get("/api/admin/orders?size=" + SEARCH_SENTINEL),
                client.get("/api/admin/orders?after=" + SEARCH_SENTINEL),
                client.get("/api/admin/orders/notanumber"),
                client.get("/api/admin/orders/987654"),
                client.get("/api/admin/orders/0"));

        assertThat(successes).extracting(AdminClient.Reply::status).containsOnly(200);
        assertThat(successes.get(0).body()).contains(NAME).contains(PHONE).doesNotContain(ADDRESS);
        assertThat(successes.get(1).body()).as("the phone search finds the order").contains(NAME);
        assertThat(successes.get(4).body()).as("an unreadable search is an empty page").contains("\"orders\":[]");
        assertThat(successes.get(5).body()).as("positive control: the detail carries the data").contains(NAME)
                .contains(PHONE).contains(ADDRESS);
        assertThat(errors).extracting(AdminClient.Reply::status).containsExactly(400, 400, 400, 400, 400, 404, 400);
        for (AdminClient.Reply error : errors) {
            assertThat(error.body()).doesNotContain(NAME).doesNotContain("7777").doesNotContain(ADDRESS)
                    .doesNotContain(SEARCH_SENTINEL);
        }

        assertThat(output.getAll()).as("positive control: the DEBUG lines are in the capture")
                .contains("VALIDATION_ERROR");
        if (trace) {
            assertThat(output.getAll()).as("positive control: TRACE lines of the parents are in the capture")
                    .contains("TRACE");
        }
        assertSilencedChildrenKeepTheirLevel();
        assertThat(output.getAll()).doesNotContain(NAME).doesNotContain(PHONE).doesNotContain("5557777")
                .doesNotContain(ADDRESS).doesNotContain(SEARCH_SENTINEL).doesNotContain("SentinelRead");
    }
}
