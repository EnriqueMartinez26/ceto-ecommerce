package ar.cetobhb.admin.controller;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.support.ContractAssertions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/admin/products} (ACAT-02, ACAT-03, ACAT-07, ACAT-06): a base product is created together with its
 * stock row, a combo has none; money leaves as a two-decimal string; every bad input is a 400 with nothing persisted and
 * with the reason a client can act on; no token is a 403.
 */
class AdminProductCreateHttpIT extends AbstractAdminHttpIT {

    private static final String PATH = "/api/admin/products";
    private static final String BASE_BODY =
            "{\"type\":\"BASE\",\"name\":\"Frasco\",\"price\":\"100.00\",\"compareAtPrice\":\"150.00\"}";

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JsonMapper json;

    private int products() {
        return jdbc.queryForObject("SELECT count(*) FROM products", Integer.class);
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    // ---- success --------------------------------------------------------------------------------------------

    @Test
    void aBaseProductIs201MatchesTheGoldenAndGetsAStockRowOfZeroAndOneAuditRow() throws Exception {
        MockHttpServletResponse response = send(adminPostJson(PATH, BASE_BODY));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentType()).startsWith("application/json");
        ContractAssertions.assertMatchesGolden(response, "admin-product-created.json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(jdbc.queryForMap("SELECT on_hand, reserved FROM inventory_stock WHERE product_id = 1"))
                .containsEntry("on_hand", 0).containsEntry("reserved", 0);
        assertThat(auditActions()).containsExactly("PRODUCT_CREATED");
    }

    /**
     * Review batch 10: the 201 describes the STORED row, it does not assume it. A trigger makes the row differ from what
     * the application wrote (version 7, inactive); the body must say so, and the version it reports must be the one the
     * next edit needs.
     */
    @Test
    void theCreatedResponseCarriesTheVersionAndTheActiveFlagOfTheStoredRowNotAssumedOnes() throws Exception {
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION shape_product_row_for_test() RETURNS trigger AS $$ BEGIN"
                    + " NEW.version := 7; NEW.active := FALSE; RETURN NEW; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER shape_product_row_for_test BEFORE INSERT ON products FOR EACH ROW"
                    + " EXECUTE FUNCTION shape_product_row_for_test()");

            JsonNode created = body(send(adminPostJson(PATH, BASE_BODY)));

            assertThat(created.get("version").asLong()).isEqualTo(7L);
            assertThat(created.get("active").asBoolean()).isFalse();
            assertThat(jdbc.queryForMap("SELECT version, active FROM products WHERE id = ?",
                    created.get("id").asLong())).containsEntry("version", 7L).containsEntry("active", false);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS shape_product_row_for_test ON products");
            jdbc.execute("DROP FUNCTION IF EXISTS shape_product_row_for_test()");
        }
    }

    @Test
    void aComboIs201MatchesTheGoldenAndHasNoStockRow() throws Exception {
        long base = catalog.createBase(product("Frasco", "100.00")).id();

        MockHttpServletResponse response = send(adminPostJson(PATH,
                "{\"type\":\"COMBO\",\"name\":\"Combo x3\",\"price\":\"270.00\",\"baseProductId\":" + base
                        + ",\"units\":3}"));

        assertThat(response.getStatus()).isEqualTo(201);
        ContractAssertions.assertMatchesGolden(response, "admin-product-created-combo.json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_stock", Integer.class)).isZero();
        assertThat(auditActions()).containsExactly("PRODUCT_CREATED");
    }

    @Test
    void pricesLeaveAsTwoDecimalStringsWhateverShapeTheyArrivedIn() throws Exception {
        JsonNode created = body(send(adminPostJson(PATH,
                "{\"type\":\"BASE\",\"name\":\"Uno\",\"price\":99.5,\"compareAtPrice\":\"100\"}")));

        assertThat(created.get("price").asString()).matches("^[0-9]{1,10}(\\.[0-9]{1,2})?$").isEqualTo("99.50");
        assertThat(created.get("compareAtPrice").asString()).isEqualTo("100.00");
    }

    @Test
    void aNewBaseProductShowsSoldOutOnTheStorefrontAndAvailableAfterAStockAdjustment() throws Exception {
        long id = body(send(adminPostJson(PATH, BASE_BODY))).get("id").asLong();

        JsonNode before = json.readTree(send(get("/api/store/products/" + id)).getContentAsString());
        assertThat(before.get("availability").asString()).isEqualTo("SOLD_OUT");

        assertThat(send(adminPostJson("/api/admin/stock/" + id + "/adjust",
                "{\"delta\":5,\"reason\":\"RESTOCK\"}")).getStatus()).isEqualTo(200);

        JsonNode after = json.readTree(send(get("/api/store/products/" + id)).getContentAsString());
        assertThat(after.get("availability").asString()).isEqualTo("AVAILABLE");
    }

    @Test
    void theNameIsStoredRawWithoutEscaping() throws Exception {
        JsonNode created = body(send(adminPostJson(PATH,
                "{\"type\":\"BASE\",\"name\":\"<b>Frasco</b> & \\\"Co\\\"\",\"price\":\"10.00\"}")));

        assertThat(created.get("name").asString()).isEqualTo("<b>Frasco</b> & \"Co\"");
        assertThat(jdbc.queryForObject("SELECT name FROM products", String.class)).isEqualTo("<b>Frasco</b> & \"Co\"");
    }

    // ---- rejected input -------------------------------------------------------------------------------------

    static Stream<Arguments> invalidWithAReason() {
        String base = "\"type\":\"BASE\"";
        return Stream.of(
                Arguments.of("price 0", "{" + base + ",\"name\":\"X\",\"price\":0}", "INVALID_PRICE_PAIR"),
                Arguments.of("price -1", "{" + base + ",\"name\":\"X\",\"price\":-1}", "INVALID_PRICE_PAIR"),
                Arguments.of("price 1e12", "{" + base + ",\"name\":\"X\",\"price\":1e12}", "INVALID_PRICE_PAIR"),
                Arguments.of("price 3 decimals", "{" + base + ",\"name\":\"X\",\"price\":\"1.234\"}",
                        "INVALID_PRICE_PAIR"),
                Arguments.of("compare equal", "{" + base + ",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"compareAtPrice\":\"10.00\"}", "INVALID_PRICE_PAIR"),
                Arguments.of("compare lower", "{" + base + ",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"compareAtPrice\":\"9.00\"}", "INVALID_PRICE_PAIR"),
                Arguments.of("name of 201", "{" + base + ",\"name\":\"" + "a".repeat(201) + "\",\"price\":\"10.00\"}",
                        "INVALID_PRODUCT_NAME"),
                Arguments.of("blank name", "{" + base + ",\"name\":\"   \",\"price\":\"10.00\"}",
                        "INVALID_PRODUCT_NAME"),
                Arguments.of("control character", "{" + base + ",\"name\":\"Fra\\u0007sco\",\"price\":\"10.00\"}",
                        "INVALID_CHARACTERS"),
                Arguments.of("format character", "{" + base + ",\"name\":\"Fra\\u200bsco\",\"price\":\"10.00\"}",
                        "INVALID_CHARACTERS"),
                Arguments.of("line separator", "{" + base + ",\"name\":\"Fra\\u2028sco\",\"price\":\"10.00\"}",
                        "INVALID_CHARACTERS"),
                Arguments.of("a base with a link", "{" + base + ",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"baseProductId\":1,\"units\":2}", "INVALID_COMBO_LINK"),
                Arguments.of("a combo without a base", "{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"units\":2}", "INVALID_COMBO_LINK"),
                Arguments.of("a combo without units", "{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"baseProductId\":1}", "INVALID_COMBO_LINK"),
                Arguments.of("combo units 1", "{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"baseProductId\":1,\"units\":1}", "INVALID_COMBO_LINK"),
                Arguments.of("combo units 101", "{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"baseProductId\":1,\"units\":101}", "INVALID_COMBO_LINK"),
                Arguments.of("combo over an unknown base", "{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"baseProductId\":987654,\"units\":2}", "INVALID_COMBO_LINK"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidWithAReason")
    void aRuleBreakingInputIs400WithTheReasonAndNothingPersisted(String label, String body, String reason)
            throws Exception {
        catalog.createBase(product("Seed", "10.00"));
        int before = products();

        MockHttpServletResponse response = send(adminPostJson(PATH, body));

        assertThat(response.getStatus()).as(label).isEqualTo(400);
        JsonNode error = body(response);
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.get("reason").asString()).isEqualTo(reason);
        assertThat(products()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_stock", Integer.class)).isZero();
        assertThat(auditRows()).isZero();
    }

    @Test
    void aComboOfAComboAndAComboOverAnInactiveBaseAreRejectedWithNothingPersisted() throws Exception {
        long base = catalog.createBase(product("Frasco", "100.00")).id();
        long combo = catalog.createCombo(product("Combo x2", "180.00"), base, 2).id();
        long retired = catalog.createBase(product("Retirado", "50.00")).id();
        catalog.setActive(retired, false);
        int before = products();

        MockHttpServletResponse ofCombo = send(adminPostJson(PATH, "{\"type\":\"COMBO\",\"name\":\"X\","
                + "\"price\":\"10.00\",\"baseProductId\":" + combo + ",\"units\":2}"));
        MockHttpServletResponse overInactive = send(adminPostJson(PATH, "{\"type\":\"COMBO\",\"name\":\"X\","
                + "\"price\":\"10.00\",\"baseProductId\":" + retired + ",\"units\":2}"));

        for (MockHttpServletResponse response : new MockHttpServletResponse[] {ofCombo, overInactive}) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(body(response).get("reason").asString()).isEqualTo("INVALID_COMBO_LINK");
        }
        assertThat(products()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    static Stream<Arguments> invalidPlain() {
        return Stream.of(
                Arguments.of("{\"name\":\"X\",\"price\":\"10.00\"}"),
                Arguments.of("{\"type\":\"BASE\",\"price\":\"10.00\"}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":null,\"price\":\"10.00\"}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\"}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\",\"price\":null}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"" + "a".repeat(1001) + "\",\"price\":\"10.00\"}"),
                Arguments.of("{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\",\"baseProductId\":0,\"units\":2}"),
                Arguments.of("{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\","
                        + "\"baseProductId\":9007199254740992,\"units\":2}"),
                Arguments.of("{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\",\"baseProductId\":1,\"units\":0}"),
                Arguments.of("{\"type\":\"COMBO\",\"name\":\"X\",\"price\":\"10.00\",\"baseProductId\":1,\"units\":1001}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\",\"price\":\"99999999999999999.00\"}"));
    }

    @ParameterizedTest
    @MethodSource("invalidPlain")
    void aMissingOrOutOfBoundsFieldIsA400ValidationErrorWithNothingPersisted(String body) throws Exception {
        MockHttpServletResponse response = send(adminPostJson(PATH, body));

        assertThat(response.getStatus()).as(body).isIn(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-validation.json", PATH);
        assertThat(products()).isZero();
        assertThat(auditRows()).isZero();
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of("{\"type\":\"FOO\",\"name\":\"X\",\"price\":\"10.00\"}"),
                Arguments.of("{\"type\":\"base\",\"name\":\"X\",\"price\":\"10.00\"}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\",\"price\":\"abc\"}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\",\"price\":true}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\",\"price\":\"10.00\",\"active\":false}"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\",\"price\":\"10.00\",\"price\":\"11.00\"}"),
                Arguments.of("[]"),
                Arguments.of("{\"type\":\"BASE\",\"name\":\"X\",\"price\":\"10.00\""),
                Arguments.of(""));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void anUnreadableBodyIsA400MalformedRequestWithNothingPersisted(String body) throws Exception {
        MockHttpServletResponse response = send(adminPostJson(PATH, body));

        assertThat(response.getStatus()).as(body).isEqualTo(400);
        ContractAssertions.assertMatchesGoldenForPath(response, "admin-error-malformed.json", PATH);
        assertThat(products()).isZero();
        assertThat(auditRows()).isZero();
    }

    @Test
    void anotherContentTypeIsA415() throws Exception {
        MockHttpServletResponse response = send(adminPost(PATH).contentType(MediaType.TEXT_PLAIN).content(BASE_BODY));

        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(products()).isZero();
    }

    @Test
    void withoutATokenIs403AndWithoutASessionIs403BeforeAuthentication() throws Exception {
        MockHttpServletResponse noToken = send(post(PATH).with(user("admin")).contentType(MediaType.APPLICATION_JSON)
                .content(BASE_BODY));
        MockHttpServletResponse anonymous = send(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BASE_BODY));

        assertThat(noToken.getStatus()).isEqualTo(403);
        ContractAssertions.assertMatchesGoldenForPath(noToken, "admin-error-forbidden.json", PATH);
        assertThat(anonymous.getStatus()).isEqualTo(403);
        assertThat(products()).isZero();
        assertThat(auditRows()).isZero();
    }
}
