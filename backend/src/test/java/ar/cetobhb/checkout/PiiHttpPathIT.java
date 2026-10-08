package ar.cetobhb.checkout;

import static ar.cetobhb.support.CatalogFixtures.product;
import static ar.cetobhb.support.ContractAssertions.assertMatchesGolden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import ar.cetobhb.support.AbstractHttpIT;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The whole HTTP path never leaks buyer data (REQ-ERR-05, CLAUDE.md section 5): sentinel name, phone and address go
 * through the real endpoint on success, validation errors, malformed bodies, a database CHECK violation (whose
 * Postgres DETAIL is the failing row) and a database failure that becomes a 500 (a trigger whose message carries
 * the row). The captured output is the WHOLE application output (MockMvc echo is off, so nothing here comes from the
 * test itself) and must never hold a sentinel; the responses never hold one either, because the contract returns no
 * buyer data. Error cases also assert the expected log line exists, so a silent logger cannot pass them vacuously.
 *
 * <p>Isolation: the probes (a CHECK constraint, a trigger and its function) are created on the SHARED {@code orders}
 * table of the test database, so failsafe must stay non-parallel (the pom pins {@code forkCount=1}, and no JUnit
 * parallel execution is configured). The constraint probe is dropped after and before every test.
 */
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PiiHttpPathIT extends AbstractHttpIT {

    private static final String PATH = "/api/checkout/orders";
    private static final String NAME = "SentinelBuyerQ7x2Name";
    private static final String PHONE_DIGITS = "3515552837";
    private static final String PHONE = "+549" + PHONE_DIGITS;
    private static final String ADDRESS = "SentinelAddressQ7x2 987";
    private static final List<String> SENTINELS = List.of(NAME, PHONE_DIGITS, ADDRESS, "SentinelQty", "Failing row");

    @Autowired
    private CatalogService catalog;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;

    @BeforeEach
    void seed() {
        // A previous run killed mid-test (or a failed @AfterEach) must not leave a probe behind for this one.
        dropProbes();
        productId = catalog.createBase(product("Solid shampoo", "45000.00")).id();
        inventory.createStock(productId, 10);
    }

    @AfterEach
    void dropProbes() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_pii_trg ON orders");
        jdbc.execute("DROP FUNCTION IF EXISTS test_pii_fn()");
        jdbc.execute("ALTER TABLE orders DROP CONSTRAINT IF EXISTS test_pii_ck");
    }

    private String body(String name, String phone, String address, String zone, String quantity) {
        return """
                {"buyer":{"name":"%s","phone":"%s","address":"%s","zone":"%s"},
                 "lines":[{"productId":%d,"quantity":%s}]}
                """.formatted(name, phone, address, zone, productId, quantity);
    }

    private MockHttpServletResponse checkout(String body) throws Exception {
        return send(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertNoSentinel(String text, String what) {
        assertThat(text).as(what).doesNotContain(SENTINELS.toArray(String[]::new));
    }

    private static String bodyOf(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private long orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    @Test
    void aSuccessfulOrderReturnsAndLogsNoBuyerData(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = checkout(body(NAME, PHONE, ADDRESS, "CORDOBA_CAPITAL", "2"));

        assertThat(response.getStatus()).isEqualTo(201);
        assertNoSentinel(bodyOf(response), "201 response body");
        assertThat(bodyOf(response)).doesNotContain("buyer", "phone", "address");
        assertNoSentinel(output.getAll(), "application output");
        assertThat(orderCount()).isEqualTo(1L);
    }

    @Test
    void validationErrorsReturnAndLogNoBuyerData(CapturedOutput output) throws Exception {
        MockHttpServletResponse badZone = checkout(body(NAME, PHONE, ADDRESS, "MARS", "2"));
        MockHttpServletResponse badPhone = checkout(body(NAME, "SENTINEL-PHONE-" + PHONE_DIGITS, ADDRESS,
                "CORDOBA_CAPITAL", "2"));
        MockHttpServletResponse badQuantity = checkout(body(NAME, PHONE, ADDRESS, "CORDOBA_CAPITAL", "0"));

        for (MockHttpServletResponse response : List.of(badZone, badPhone, badQuantity)) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertNoSentinel(bodyOf(response), "400 response body");
            assertThat(bodyOf(response)).doesNotContain("SENTINEL-PHONE");
        }
        assertNoSentinel(output.getAll(), "application output");
        assertThat(output.getAll()).doesNotContain("SENTINEL-PHONE");
        assertThat(orderCount()).isZero();
    }

    @Test
    void malformedBodiesReturnAndLogNoBuyerData(CapturedOutput output) throws Exception {
        MockHttpServletResponse truncated = checkout("{\"buyer\":{\"name\":\"" + NAME + "\",\"phone\":\"" + PHONE
                + "\",\"address\":\"" + ADDRESS);
        MockHttpServletResponse wrongType = checkout(body(NAME, PHONE, ADDRESS, "CORDOBA_CAPITAL", "\"SentinelQty\""));
        MockHttpServletResponse unknownField = checkout(body(NAME, PHONE, ADDRESS, "CORDOBA_CAPITAL", "2")
                .replace("\"buyer\"", "\"SentinelQty\":\"" + NAME + "\",\"buyer\""));

        for (MockHttpServletResponse response : List.of(truncated, wrongType, unknownField)) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertNoSentinel(bodyOf(response), "400 response body");
        }
        assertNoSentinel(output.getAll(), "application output");
        assertThat(orderCount()).isZero();
    }

    @Test
    void aDatabaseCheckViolationDoesNotLeakTheFailingRow(CapturedOutput output) throws Exception {
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT test_pii_ck CHECK (buyer_name <> '" + NAME + "')");

        MockHttpServletResponse response = checkout(body(NAME, PHONE, ADDRESS, "CORDOBA_CAPITAL", "2"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(bodyOf(response)).contains("\"code\":\"CONFLICT\"");
        assertMatchesGolden(response, "error-conflict.json");
        assertNoSentinel(bodyOf(response), "409 response body");
        assertThat(output.getAll()).contains("CONFLICT", "constraint=test_pii_ck");
        assertNoSentinel(output.getAll(), "application output");
        assertThat(orderCount()).isZero();
    }

    @Test
    void anUnexpectedDatabaseFailureIs500WithoutTheRowInTheBodyOrTheLog(CapturedOutput output) throws Exception {
        jdbc.execute("""
                CREATE FUNCTION test_pii_fn() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'cannot store order of % at % (%)', NEW.buyer_name, NEW.buyer_phone_e164,
                        NEW.shipping_address;
                END $$""");
        jdbc.execute("CREATE TRIGGER test_pii_trg BEFORE INSERT ON orders FOR EACH ROW EXECUTE FUNCTION test_pii_fn()");

        MockHttpServletResponse response = checkout(body(NAME, PHONE, ADDRESS, "CORDOBA_CAPITAL", "2"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(bodyOf(response)).contains("\"code\":\"INTERNAL_ERROR\"");
        assertMatchesGolden(response, "error-internal.json");
        assertNoSentinel(bodyOf(response), "500 response body");
        // P0001 = the trigger's RAISE EXCEPTION, whose message carries the whole row: the failure is the intended one.
        assertThat(output.getAll()).contains("INTERNAL_ERROR", "sqlState=P0001");
        assertNoSentinel(output.getAll(), "application output");
        assertThat(orderCount()).isZero();
    }
}
