package ar.cetobhb.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.admin.domain.AuditAction;
import ar.cetobhb.admin.domain.AuditEntry;
import ar.cetobhb.admin.domain.StockAdjustmentReason;
import ar.cetobhb.admin.repository.AdminAuditLog;
import ar.cetobhb.admin.repository.AdminAuditLogRepository;
import ar.cetobhb.admin.service.AdminAuditService;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.AuditLogScan;
import ar.cetobhb.support.CatalogFixtures;
import ar.cetobhb.support.MutableClock;
import jakarta.persistence.Id;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The audit primitive (AD-A14): same transaction as the caller, injected clock, closed shape, no PII. */
class AdminAuditServiceIT extends AbstractPostgresIT {

    @Autowired
    private AdminAuditService audit;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private MutableClock clock;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
    }

    @Test
    void recordInsideATransactionWritesOneRowStampedWithTheInjectedClock() {
        Instant at = Instant.parse("2026-05-01T08:30:15.123456Z");
        clock.set(at);

        tx().executeWithoutResult(status -> audit.record(AuditEntry.order(AuditAction.ORDER_PAID, 41L)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_log");
        assertThat(rows()).isEqualTo(1);
        assertThat(row.get("action")).isEqualTo("ORDER_PAID");
        assertThat(row.get("entity_type")).isEqualTo("ORDER");
        assertThat(row.get("entity_id")).isEqualTo(41L);
        assertThat(row.get("reason")).isNull();
        assertThat(row.get("before_value")).isNull();
        assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(at);
    }

    @Test
    void theStockAdjustmentValuesAndReasonArePersisted() {
        tx().executeWithoutResult(status -> audit.record(
                AuditEntry.stockAdjusted(7L, StockAdjustmentReason.RESTOCK, 5, 25)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM admin_audit_log");
        assertThat(row.get("reason")).isEqualTo("RESTOCK");
        assertThat((BigDecimal) row.get("before_value")).isEqualByComparingTo("5");
        assertThat((BigDecimal) row.get("after_value")).isEqualByComparingTo("25");
    }

    @Test
    void recordOutsideATransactionIsRefusedAndWritesNothing() {
        assertThatThrownBy(() -> audit.record(AuditEntry.order(AuditAction.ORDER_PAID, 41L)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rows()).isZero();
    }

    @Test
    void aCallerThatRollsBackLeavesNoRow() {
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            audit.record(AuditEntry.order(AuditAction.ORDER_PAID, 41L));
            throw new IllegalStateException("domain failure after the audit call");
        })).isInstanceOf(IllegalStateException.class);
        tx().executeWithoutResult(status -> {
            audit.record(AuditEntry.order(AuditAction.ORDER_DELIVERED, 42L));
            status.setRollbackOnly();
        });

        assertThat(rows()).isZero();
    }

    @Test
    void theEntityIsImmutableAndHasNoSetters() {
        assertThat(AdminAuditLog.class).hasAnnotation(Immutable.class);
        List<String> mutators = Arrays.stream(AdminAuditLog.class.getDeclaredMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set") || name.startsWith("update") || name.startsWith("mark"))
                .toList();
        assertThat(mutators).isEmpty();
        assertThat(Arrays.stream(AdminAuditLog.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Id.class)).count()).isEqualTo(1);
    }

    @Test
    void theRepositoryOffersSaveAndNothingElse() {
        List<String> methods = Arrays.stream(AdminAuditLogRepository.class.getMethods())
                .map(Method::getName).sorted().toList();

        assertThat(methods).containsExactly("save");
    }

    @Test
    void noTextColumnEverHoldsPersonalDataEvenWhenTheAuditedEntityDoes() {
        String sentinel = "Zzsentinel Perez 3515550199 Calle Falsa 123";
        ProductSnapshot product = catalog.createBase(CatalogFixtures.product(sentinel, "100.00"));

        tx().executeWithoutResult(status -> {
            audit.record(AuditEntry.product(AuditAction.PRODUCT_CREATED, product.id()));
            audit.record(AuditEntry.stockAdjusted(product.id(), StockAdjustmentReason.OTHER, 0, 3));
            audit.record(AuditEntry.priceChanged(product.id(), new BigDecimal("100.00"), new BigDecimal("120.00")));
        });

        assertThat(rows()).isEqualTo(3);
        assertThat(AuditLogScan.scannedColumns(jdbc)).isGreaterThanOrEqualTo(8);
        for (String fragment : List.of("zzsentinel", "perez", "3515550199", "calle falsa")) {
            assertThat(AuditLogScan.columnsContaining(jdbc, fragment)).as(fragment).isEmpty();
        }
        // the scan itself bites: it finds the action name that really is in the table
        assertThat(AuditLogScan.columnsContaining(jdbc, "PRICE_CHANGED")).containsExactly("action");
    }
}
