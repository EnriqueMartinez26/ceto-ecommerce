package ar.cetobhb.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.domain.AuditAction;
import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.orders.domain.CancelReason;
import ar.cetobhb.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The Java enums and the database CHECK sets (V4) are two copies of one closed set; this test keeps them equal. */
class AuditActionIT extends AbstractPostgresIT {

    private static final Pattern LITERAL = Pattern.compile("'([A-Z_]+)'");

    @Autowired
    private JdbcTemplate jdbc;

    private Set<String> literalsOf(String constraint) {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraint);
        Set<String> literals = new TreeSet<>();
        Matcher matcher = LITERAL.matcher(definition);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    private static AuditEntry sampleOf(AuditAction action) {
        return switch (action) {
            case ORDER_PAID, ORDER_DISPATCHED, ORDER_DELIVERED -> AuditEntry.order(action, 41L);
            case ORDER_CANCELLED -> AuditEntry.orderCancelled(41L);
            case STOCK_ADJUSTED -> AuditEntry.stockAdjusted(7L, StockAdjustmentReason.RESTOCK, 5, 25);
            case PRICE_CHANGED -> AuditEntry.priceChanged(7L, new BigDecimal("100.00"), new BigDecimal("150.00"));
            case PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_ACTIVATED, PRODUCT_DEACTIVATED ->
                    AuditEntry.product(action, 7L);
        };
    }

    @Test
    void theActionEnumEqualsTheCheckSet() {
        Set<String> enumNames = new TreeSet<>();
        EnumSet.allOf(AuditAction.class).forEach(a -> enumNames.add(a.name()));

        assertThat(literalsOf("admin_audit_log_action_ck")).isEqualTo(enumNames);
    }

    @Test
    void theReasonEnumsEqualTheCheckSet() {
        Set<String> expected = new TreeSet<>(Set.of("STOCK_ADJUSTED", "ORDER_CANCELLED",
                CancelReason.CANCELLED_BY_ADMIN.name()));
        EnumSet.allOf(StockAdjustmentReason.class).forEach(r -> expected.add(r.name()));

        assertThat(literalsOf("admin_audit_log_reason_ck")).isEqualTo(expected);
    }

    @Test
    void theEntityTypesOfTheEnumAreTheOnesTheCheckAccepts() {
        Set<String> types = new TreeSet<>();
        EnumSet.allOf(AuditAction.class).forEach(a -> types.add(a.entityType().name()));

        assertThat(literalsOf("admin_audit_log_entity_ck")).isEqualTo(types);
    }

    @Test
    void everyActionBuildsAnEntryTheDatabaseAccepts() {
        for (AuditAction action : AuditAction.values()) {
            AuditEntry entry = sampleOf(action);

            jdbc.update("INSERT INTO admin_audit_log "
                            + "(occurred_at, action, entity_type, entity_id, reason, before_value, after_value) "
                            + "VALUES (now(), ?, ?, ?, ?, ?, ?)",
                    entry.action().name(), entry.entityType().name(), entry.entityId(), entry.reason(),
                    entry.beforeValue(), entry.afterValue());
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class))
                .isEqualTo(AuditAction.values().length);
    }
}
