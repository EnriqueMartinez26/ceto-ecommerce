package ar.cetobhb.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.DatabaseCleaner;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3: every orders / order_items constraint, proven on a real Postgres with raw SQL (the Java code is not
 * the line of defense). CHECK constraints are evaluated in name order, so each case violates exactly one.
 */
class OrderConstraintsIT extends AbstractPostgresIT {

    private static final String CHECK = "23514";
    private static final String UNIQUE = "23505";
    private static final String FK = "23503";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-07T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    private long insertProduct() {
        return jdbc.queryForObject(
                "INSERT INTO products (name, price) VALUES ('base', 100.00) RETURNING id", Long.class);
    }

    private Map<String, Object> pendingOrder() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("order_number", "CETO-000001");
        o.put("status", "PAGO_PENDIENTE");
        o.put("cancel_reason", null);
        o.put("idempotency_key", UUID.randomUUID().toString());
        o.put("request_fingerprint", "a".repeat(64));
        o.put("buyer_name", "Buyer");
        o.put("buyer_phone_e164", "+5493515550000");
        o.put("shipping_address", "Street 1");
        o.put("shipping_zone", "CORDOBA_CAPITAL");
        o.put("total", "100.00");
        o.put("reserved_until", NOW.plusHours(24));
        o.put("created_at", NOW);
        o.put("updated_at", NOW);
        o.put("paid_at", null);
        o.put("dispatched_at", null);
        o.put("delivered_at", null);
        o.put("cancelled_at", null);
        return o;
    }

    private Map<String, Object> with(Map<String, Object> base, Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private String sqlFor(String table, Map<String, Object> row) {
        StringBuilder values = new StringBuilder();
        for (String column : row.keySet()) {
            String cast = switch (column) {
                case "idempotency_key" -> "CAST(? AS uuid)";
                case "total", "unit_price", "line_total" -> "CAST(? AS numeric)";
                case "reserved_until", "created_at", "updated_at", "paid_at", "dispatched_at", "delivered_at",
                        "cancelled_at" -> "CAST(? AS timestamptz)";
                default -> "?";
            };
            values.append(values.isEmpty() ? "" : ", ").append(cast);
        }
        return "INSERT INTO " + table + " (" + String.join(", ", row.keySet()) + ") VALUES (" + values + ")";
    }

    private long insertOrder(Map<String, Object> row) {
        jdbc.update(sqlFor("orders", row), row.values().toArray());
        return jdbc.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class,
                row.get("order_number"));
    }

    private void insertItem(Map<String, Object> row) {
        jdbc.update(sqlFor("order_items", row), row.values().toArray());
    }

    private void assertOrderRejected(Map<String, Object> row, String sqlState, String constraint) {
        assertRejected(sqlFor("orders", row), row.values().toArray(), sqlState, constraint);
    }

    private void assertItemRejected(Map<String, Object> row, String sqlState, String constraint) {
        assertRejected(sqlFor("order_items", row), row.values().toArray(), sqlState, constraint);
    }

    private Map<String, Object> baseLine(long orderId, long productId) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("order_id", orderId);
        i.put("line_no", (short) 1);
        i.put("product_id", productId);
        i.put("product_name", "base");
        i.put("is_combo", false);
        i.put("unit_price", "100.00");
        i.put("quantity", 2);
        i.put("base_product_id", productId);
        i.put("base_units_per_unit", 1);
        i.put("base_units_total", 2);
        i.put("line_total", "200.00");
        return i;
    }

    private void assertRejected(String sql, Object[] args, String sqlState, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(root).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) root).getSQLState()).isEqualTo(sqlState);
                    assertThat(root.getMessage()).contains("\"" + constraint + "\"");
                });
    }

    // ---- orders ----

    @Test
    void aValidOrderOfEveryStatusIsAcceptedAndNoOrderIsSeeded() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        Map<String, Object> paid = with(pendingOrder(), "order_number", "CETO-000002", "status", "PAGADO",
                "paid_at", NOW);
        Map<String, Object> cancelled = with(pendingOrder(), "order_number", "CETO-000003", "status", "CANCELADO",
                "cancel_reason", "CANCELLED_BY_BUYER", "cancelled_at", NOW);
        assertThatCode(() -> {
            insertOrder(pendingOrder());
            insertOrder(paid);
            insertOrder(cancelled);
        }).doesNotThrowAnyException();
    }

    @Test
    void statusOutsideTheFiveStatesIsRejected() {
        assertOrderRejected(with(pendingOrder(), "status", "SHIPPED"), CHECK, "orders_status_ck");
    }

    @Test
    void cancelReasonOutsideTheThreeReasonsIsRejected() {
        assertOrderRejected(with(pendingOrder(), "status", "CANCELADO", "cancel_reason", "OTHER",
                "cancelled_at", NOW), CHECK, "orders_cancel_reason_ck");
    }

    @Test
    void cancelledWithoutAReasonIsRejected() {
        assertOrderRejected(with(pendingOrder(), "status", "CANCELADO", "cancelled_at", NOW),
                CHECK, "orders_cancel_pair_ck");
    }

    @Test
    void aReasonOnANonCancelledOrderIsRejected() {
        assertOrderRejected(with(pendingOrder(), "cancel_reason", "EXPIRED"), CHECK, "orders_cancel_pair_ck");
    }

    @Test
    void cancelledAtMustBePresentExactlyWhenCancelled() {
        assertOrderRejected(with(pendingOrder(), "status", "CANCELADO", "cancel_reason", "EXPIRED"),
                CHECK, "orders_cancel_pair_ck");
        assertOrderRejected(with(pendingOrder(), "cancelled_at", NOW), CHECK, "orders_cancel_pair_ck");
    }

    @Test
    void anExpiredOrderCannotCarryAPaymentTimestamp() {
        assertOrderRejected(with(pendingOrder(), "status", "CANCELADO", "cancel_reason", "EXPIRED",
                "cancelled_at", NOW, "paid_at", NOW), CHECK, "orders_expired_unpaid_ck");
    }

    @Test
    void paidDispatchedAndDeliveredRequirePaidAt() {
        for (String status : List.of("PAGADO", "DESPACHADO", "ENTREGADO")) {
            assertOrderRejected(with(pendingOrder(), "status", status), CHECK, "orders_paid_at_ck");
        }
    }

    @Test
    void orderNumberMustBeUnique() {
        insertOrder(pendingOrder());
        assertOrderRejected(pendingOrder(), UNIQUE, "orders_order_number_uk");
    }

    @Test
    void orderNumberFormatAcceptsSixOrMoreDigitsOnly() {
        assertThatCode(() -> {
            insertOrder(with(pendingOrder(), "order_number", "CETO-1234567"));
            insertOrder(with(pendingOrder(), "order_number", "CETO-000012"));
        }).doesNotThrowAnyException();
        for (String bad : List.of("CETO-12", "ceto-000012", "CETO-00001A", "000012")) {
            assertOrderRejected(with(pendingOrder(), "order_number", bad), CHECK, "orders_order_number_ck");
        }
    }

    @Test
    void idempotencyKeyMustBeUnique() {
        Map<String, Object> first = pendingOrder();
        insertOrder(first);
        assertOrderRejected(with(pendingOrder(), "order_number", "CETO-000002", "idempotency_key",
                first.get("idempotency_key")), UNIQUE, "orders_idempotency_key_uk");
    }

    @Test
    void idempotencyKeyMustBeAUuid() {
        assertThatThrownBy(() -> insertOrder(with(pendingOrder(), "idempotency_key", "not-a-uuid")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(e))
                        .getSQLState()).isEqualTo("22P02"));
    }

    @Test
    void shippingZoneOutsideTheTwoZonesIsRejected() {
        assertOrderRejected(with(pendingOrder(), "shipping_zone", "MENDOZA"), CHECK, "orders_shipping_zone_ck");
    }

    @Test
    void phoneMustBeE164() {
        assertThatCode(() -> {
            insertOrder(with(pendingOrder(), "order_number", "CETO-000002", "buyer_phone_e164", "+12345678"));
            insertOrder(with(pendingOrder(), "order_number", "CETO-000003", "buyer_phone_e164",
                    "+123456789012345"));
        }).doesNotThrowAnyException();
        for (String bad : List.of("03515550000", "+03515550000", "+1234567",
                "+54 9351 555", "5493515550000")) {
            assertOrderRejected(with(pendingOrder(), "buyer_phone_e164", bad), CHECK, "orders_phone_e164_ck");
        }
        // 16 digits plus '+' does not even fit the VARCHAR(16) column: string_data_right_truncation.
        assertTooLong(with(pendingOrder(), "buyer_phone_e164", "+1234567890123456"));
    }

    @Test
    void fingerprintMustBe64LowercaseHexCharacters() {
        for (String bad : List.of("A".repeat(64), "a".repeat(63), "g".repeat(64))) {
            assertOrderRejected(with(pendingOrder(), "request_fingerprint", bad), CHECK, "orders_fingerprint_ck");
        }
        assertTooLong(with(pendingOrder(), "request_fingerprint", "a".repeat(65)));
    }

    @Test
    void totalMustBePositive() {
        assertOrderRejected(with(pendingOrder(), "total", "0.00"), CHECK, "orders_total_ck");
        assertOrderRejected(with(pendingOrder(), "total", "-1.00"), CHECK, "orders_total_ck");
    }

    @Test
    void reservedUntilMustBeAfterCreation() {
        assertOrderRejected(with(pendingOrder(), "reserved_until", NOW), CHECK, "orders_reserved_until_ck");
    }

    private void assertTooLong(Map<String, Object> row) {
        assertThatThrownBy(() -> jdbc.update(sqlFor("orders", row), row.values().toArray()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(((SQLException) root).getSQLState()).isEqualTo("22001");
                    assertThat(root.getMessage()).contains("value too long");
                });
    }

    @Test
    void textColumnsRejectValuesOverTheirLength() {
        assertTooLong(with(pendingOrder(), "buyer_name", "x".repeat(121)));
        assertTooLong(with(pendingOrder(), "shipping_address", "x".repeat(501)));
        assertThatCode(() -> insertOrder(with(pendingOrder(), "buyer_name", "x".repeat(120),
                "shipping_address", "y".repeat(500)))).doesNotThrowAnyException();
    }

    @Test
    void theExpiryIndexIsPartialOnPendingPayment() {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'orders' "
                        + "AND indexname = 'orders_pending_expiry_idx'", String.class);
        assertThat(defs).singleElement().satisfies(def -> assertThat(def)
                .contains("(reserved_until, id)")
                .contains("WHERE ((status)::text = 'PAGO_PENDIENTE'::text)"));
    }

    @Test
    void theOrderNumberSequenceYieldsOneThenTwoAndTheCleanerRestartsIt() {
        assertThat(jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class)).isEqualTo(2L);
        DatabaseCleaner.clean(jdbc);
        assertThat(jdbc.queryForObject("SELECT nextval('order_number_seq')", Long.class)).isEqualTo(1L);
    }

    // ---- order_items ----

    @Test
    void aValidBaseLineAndComboLineAreAccepted() {
        long base = insertProduct();
        long combo = insertProduct();
        long order = insertOrder(pendingOrder());
        Map<String, Object> comboLine = with(baseLine(order, combo), "line_no", (short) 2, "is_combo", true,
                "base_product_id", base, "base_units_per_unit", 3, "base_units_total", 6);
        assertThatCode(() -> {
            insertItem(baseLine(order, base));
            insertItem(comboLine);
        }).doesNotThrowAnyException();
    }

    @Test
    void itemRowsRequireAnExistingOrderAndProduct() {
        long product = insertProduct();
        long order = insertOrder(pendingOrder());
        assertItemRejected(baseLine(99999L, product), FK, "order_items_order_id_fkey");
        assertItemRejected(with(baseLine(order, product), "product_id", 99999L, "base_product_id", 99999L),
                FK, "order_items_product_id_fkey");
    }

    @Test
    void lineNumberAndProductMustBeUniquePerOrder() {
        long product = insertProduct();
        long other = insertProduct();
        long order = insertOrder(pendingOrder());
        insertItem(baseLine(order, product));
        assertItemRejected(baseLine(order, other), UNIQUE, "order_items_line_uk");
        assertItemRejected(with(baseLine(order, product), "line_no", (short) 2), UNIQUE, "order_items_product_uk");
    }

    @Test
    void quantityMustBeAtLeastOne() {
        long product = insertProduct();
        long order = insertOrder(pendingOrder());
        assertItemRejected(with(baseLine(order, product), "quantity", 0, "base_units_total", 0,
                "line_total", "0.00"), CHECK, "order_items_quantity_ck");
    }

    @Test
    void baseUnitsMathMustHold() {
        long product = insertProduct();
        long order = insertOrder(pendingOrder());
        assertItemRejected(with(baseLine(order, product), "base_units_total", 3), CHECK, "order_items_base_units_ck");
        assertItemRejected(with(baseLine(order, product), "base_units_per_unit", 0, "base_units_total", 0),
                CHECK, "order_items_base_units_ck");
    }

    @Test
    void comboAndBaseLinesMustHaveTheirShape() {
        long base = insertProduct();
        long combo = insertProduct();
        long order = insertOrder(pendingOrder());
        // combo pointing at itself
        assertItemRejected(with(baseLine(order, combo), "is_combo", true, "base_units_per_unit", 3,
                "base_units_total", 6), CHECK, "order_items_combo_ck");
        // combo of a single unit
        assertItemRejected(with(baseLine(order, combo), "is_combo", true, "base_product_id", base,
                "base_units_per_unit", 1, "base_units_total", 2), CHECK, "order_items_combo_ck");
        // base line whose stock product differs from the line product
        assertItemRejected(with(baseLine(order, base), "base_product_id", combo), CHECK, "order_items_combo_ck");
        // base line with more than one unit per unit
        assertItemRejected(with(baseLine(order, base), "base_units_per_unit", 2, "base_units_total", 4),
                CHECK, "order_items_combo_ck");
    }

    @Test
    void priceMustBePositiveAndTheLineTotalMustMatch() {
        long product = insertProduct();
        long order = insertOrder(pendingOrder());
        assertItemRejected(with(baseLine(order, product), "unit_price", "0.00", "line_total", "0.00"),
                CHECK, "order_items_price_ck");
        assertItemRejected(with(baseLine(order, product), "line_total", "199.99"), CHECK, "order_items_price_ck");
    }

    @Test
    void columnTypesMatchTheJpaContract() {
        assertThat(columnType("order_items", "line_no")).isEqualTo("smallint");
        assertThat(columnType("orders", "request_fingerprint")).isEqualTo("character varying");
        assertThat(columnType("orders", "idempotency_key")).isEqualTo("uuid");
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?", String.class, table, column);
    }
}
