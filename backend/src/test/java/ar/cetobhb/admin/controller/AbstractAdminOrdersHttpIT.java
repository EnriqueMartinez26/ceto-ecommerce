package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.orders.domain.Buyer;
import ar.cetobhb.orders.domain.PlaceOrderCommand;
import ar.cetobhb.orders.domain.ShippingZone;
import ar.cetobhb.orders.service.OrderLifecycleService;
import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.support.AbstractHttpIT;
import ar.cetobhb.support.MutableClock;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared seed and request helpers of the admin order HTTP contract tests on MockMvc. Requests are authenticated with
 * {@code user()}: that post processor installs its own security context repository, so these tests make no assertion
 * about server sessions (those live in the real-server ITs).
 */
abstract class AbstractAdminOrdersHttpIT extends AbstractHttpIT {

    @Autowired
    protected CatalogService catalog;

    @Autowired
    protected InventoryService inventory;

    @Autowired
    protected OrderPlacementService placement;

    @Autowired
    protected OrderLifecycleService lifecycle;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    protected long base;

    @BeforeEach
    void seedBaseProduct() {
        base = catalog.createBase(product("Frasco", "100.00")).id();
        inventory.createStock(base, 1000);
    }

    protected long place(String name, String phone, String address, int quantity) {
        return placement.placeOrder(new PlaceOrderCommand(UUID.randomUUID().toString(),
                new Buyer(name, phone, address, ShippingZone.CORDOBA_CAPITAL),
                List.of(new PlaceOrderCommand.Line(base, quantity)))).orderId();
    }

    protected static MockHttpServletRequestBuilder adminGet(String path) {
        return get(path).with(user("admin"));
    }

    /**
     * An authenticated POST with the double-submit token written by hand (cookie and header carry the same value).
     * The {@code csrf()} post processor is NOT used on purpose: it swaps the CSRF token repository of the shared
     * MockMvc filter for a session-backed one for good, and every later test of the same context that expects the
     * XSRF cookie ({@code AdminSessionEndpointIT}) would fail depending on the class order.
     */
    protected static MockHttpServletRequestBuilder adminPost(String path) {
        return post(path).with(user("admin")).cookie(new Cookie("__Host-XSRF-TOKEN", XSRF_TOKEN))
                .header("X-XSRF-TOKEN", XSRF_TOKEN);
    }

    protected static final String XSRF_TOKEN = "test-xsrf-token-0123456789";

    protected int auditRows() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
    }
}
