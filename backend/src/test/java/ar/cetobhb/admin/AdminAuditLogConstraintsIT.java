package ar.cetobhb.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.AbstractPostgresIT;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D-20261008-11: the audit table accepts only closed sets (no free text, so no personal data) and is append-only.
 * Raw SQL on a real Postgres: the database is the guard, whatever the Java code does. Postgres checks NOT NULL first,
 * then CHECK constraints in alphabetical order, so each case is built to violate exactly one constraint.
 */
class AdminAuditLogConstraintsIT extends AbstractPostgresIT {

    private static final String INSERT = "INSERT INTO admin_audit_log "
            + "(occurred_at, action, entity_type, entity_id, reason, before_value, after_value) "
            + "VALUES (now(), ?, ?, ?, ?, ?, ?)";

    @Autowired
    private JdbcTemplate jdbc;

    /** Runs a statement that must fail and returns the SQLException at the root of the failure. */
    private SQLException rootOf(Runnable statement) {
        Throwable[] thrown = new Throwable[1];
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> thrown[0] = NestedExceptionUtils.getMostSpecificCause(e));
        assertThat(thrown[0]).isInstanceOf(SQLException.class);
        return (SQLException) thrown[0];
    }

    private void assertCheckViolation(String constraint, Object... args) {
        SQLException root = rootOf(() -> jdbc.update(INSERT, args));
        assertThat(root.getSQLState()).isEqualTo("23514");
        assertThat(root.getMessage()).contains("\"" + constraint + "\"");
        assertThat(rowCount()).isZero();
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
    }

    private void insertValid(String action, String entityType, long entityId, String reason, Object before,
            Object after) {
        jdbc.update(INSERT, action, entityType, entityId, reason, before, after);
    }

    @Test
    void unknownActionIsRejected() {
        assertCheckViolation("admin_audit_log_action_ck", "ORDER_REFUNDED", "ORDER", 1L, null, null, null);
    }

    @Test
    void nullEntityIdIsRejectedAsNotNull() {
        SQLException root = rootOf(() -> jdbc.update(INSERT, "ORDER_PAID", "ORDER", null, null, null, null));
        assertThat(root.getSQLState()).isEqualTo("23502");
        assertThat(rowCount()).isZero();
    }

    @Test
    void entityIdBelowOneIsRejected() {
        assertCheckViolation("admin_audit_log_entity_ck", "ORDER_PAID", "ORDER", 0L, null, null, null);
        assertCheckViolation("admin_audit_log_entity_ck", "ORDER_PAID", "ORDER", -5L, null, null, null);
    }

    @Test
    void orderActionOnAProductEntityIsRejectedAndTheReverseToo() {
        assertCheckViolation("admin_audit_log_entity_ck", "ORDER_PAID", "PRODUCT", 1L, null, null, null);
        assertCheckViolation("admin_audit_log_entity_ck", "PRODUCT_CREATED", "ORDER", 1L, null, null, null);
        assertCheckViolation("admin_audit_log_entity_ck", "STOCK_ADJUSTED", "ORDER", 1L, "RESTOCK", 1, 2);
    }

    @Test
    void unknownEntityTypeIsRejected() {
        assertCheckViolation("admin_audit_log_entity_ck", "PRODUCT_CREATED", "CUSTOMER", 1L, null, null, null);
    }

    @Test
    void freeTextReasonIsRejected() {
        assertCheckViolation("admin_audit_log_reason_ck", "STOCK_ADJUSTED", "PRODUCT", 1L,
                "Mrs Perez +5493515550199", 1, 2);
        assertCheckViolation("admin_audit_log_reason_ck", "ORDER_CANCELLED", "ORDER", 1L, "EXPIRED", null, null);
        assertCheckViolation("admin_audit_log_reason_ck", "STOCK_ADJUSTED", "PRODUCT", 1L, null, 1, 2);
        // A NULL reason must not slip through three-valued logic on the actions that require one.
        assertCheckViolation("admin_audit_log_reason_ck", "ORDER_CANCELLED", "ORDER", 1L, null, null, null);
    }

    @Test
    void reasonOnAnActionThatTakesNoneIsRejected() {
        assertCheckViolation("admin_audit_log_reason_ck", "PRODUCT_UPDATED", "PRODUCT", 1L, "RESTOCK", null, null);
        assertCheckViolation("admin_audit_log_reason_ck", "ORDER_PAID", "ORDER", 1L, "CANCELLED_BY_ADMIN", null,
                null);
    }

    @Test
    void stockAndPriceActionsNeedBothValuesAndOthersNone() {
        assertCheckViolation("admin_audit_log_values_ck", "STOCK_ADJUSTED", "PRODUCT", 1L, "RESTOCK", null, null);
        assertCheckViolation("admin_audit_log_values_ck", "STOCK_ADJUSTED", "PRODUCT", 1L, "RESTOCK", 1, null);
        assertCheckViolation("admin_audit_log_values_ck", "PRICE_CHANGED", "PRODUCT", 1L, null, null, 5);
        assertCheckViolation("admin_audit_log_values_ck", "ORDER_PAID", "ORDER", 1L, null, 1, 2);
    }

    @Test
    void oversizeValuesAreRejected() {
        String longText = "X".repeat(31);
        assertThat(rootOf(() -> jdbc.update(INSERT, longText, "ORDER", 1L, null, null, null)).getSQLState())
                .isEqualTo("22001");
        assertThat(rootOf(() -> jdbc.update(INSERT, "STOCK_ADJUSTED", "PRODUCT", 1L, longText, 1, 2)).getSQLState())
                .isEqualTo("22001");
        assertThat(rootOf(() -> jdbc.update(INSERT, "ORDER_PAID", "X".repeat(11), 1L, null, null, null))
                .getSQLState()).isEqualTo("22001");
        assertThat(rootOf(() -> jdbc.update(INSERT, "PRICE_CHANGED", "PRODUCT", 1L, null, 1, new java.math.BigDecimal(
                "10000000000.00"))).getSQLState()).isEqualTo("22003");
        assertThat(rowCount()).isZero();
    }

    @Test
    void aValidRowPerActionIsAccepted() {
        assertThatCode(() -> {
            insertValid("ORDER_PAID", "ORDER", 1L, null, null, null);
            insertValid("ORDER_DISPATCHED", "ORDER", 1L, null, null, null);
            insertValid("ORDER_DELIVERED", "ORDER", 1L, null, null, null);
            insertValid("ORDER_CANCELLED", "ORDER", 1L, "CANCELLED_BY_ADMIN", null, null);
            for (String reason : new String[] {"COUNT_CORRECTION", "RESTOCK", "DAMAGED_OR_LOST", "RETURN", "OTHER"}) {
                insertValid("STOCK_ADJUSTED", "PRODUCT", 2L, reason, 5, 25);
            }
            insertValid("PRODUCT_CREATED", "PRODUCT", 2L, null, null, null);
            insertValid("PRODUCT_UPDATED", "PRODUCT", 2L, null, null, null);
            insertValid("PRICE_CHANGED", "PRODUCT", 2L, null, new java.math.BigDecimal("100.00"),
                    new java.math.BigDecimal("150.50"));
            insertValid("PRODUCT_ACTIVATED", "PRODUCT", 2L, null, null, null);
            insertValid("PRODUCT_DEACTIVATED", "PRODUCT", 2L, null, null, null);
        }).doesNotThrowAnyException();
        assertThat(rowCount()).isEqualTo(14);
    }

    @Test
    void updateIsRejectedAndTheRowStaysIntact() {
        insertValid("ORDER_PAID", "ORDER", 7L, null, null, null);

        SQLException root = rootOf(() -> jdbc.update("UPDATE admin_audit_log SET entity_id = 8"));

        assertThat(root.getSQLState()).isEqualTo("P0001");
        assertThat(root.getMessage()).contains("append-only");
        assertThat(jdbc.queryForObject("SELECT entity_id FROM admin_audit_log", Long.class)).isEqualTo(7L);
    }

    @Test
    void deleteIsRejectedAndTheRowStaysIntact() {
        insertValid("ORDER_PAID", "ORDER", 7L, null, null, null);

        SQLException root = rootOf(() -> jdbc.update("DELETE FROM admin_audit_log"));

        assertThat(root.getSQLState()).isEqualTo("P0001");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void truncateIsRejectedAndTheRowStaysIntact() {
        insertValid("ORDER_PAID", "ORDER", 7L, null, null, null);

        SQLException root = rootOf(() -> jdbc.execute("TRUNCATE TABLE admin_audit_log"));

        assertThat(root.getSQLState()).isEqualTo("P0001");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void statementsThatMatchZeroRowsAreRejectedToo() {
        assertThat(rootOf(() -> jdbc.update("UPDATE admin_audit_log SET entity_id = 8 WHERE id = -1"))
                .getSQLState()).isEqualTo("P0001");
        assertThat(rootOf(() -> jdbc.update("DELETE FROM admin_audit_log WHERE id = -1")).getSQLState())
                .isEqualTo("P0001");
        assertThat(rootOf(() -> jdbc.execute("TRUNCATE TABLE admin_audit_log")).getSQLState()).isEqualTo("P0001");
    }
}
