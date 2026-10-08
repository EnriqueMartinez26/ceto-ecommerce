package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.support.AbstractPostgresIT;
import ar.cetobhb.support.LockProbe;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Review batch 4, items 7 and 8: deterministic hold-and-probe races on product rows (REQ-CAT-02, ACAT-04, D-20261008-14,
 * D-20261008-28). One transaction holds a product row after a real facade call; the other call runs on another thread,
 * and the test first waits (polling pg_stat_activity / pg_locks, 5 s deadline) until Postgres shows that backend
 * waiting on a lock, and only then asserts it has not finished. The holder then commits and the outcome is checked
 * against the persisted state and the audit rows.
 */
class AdminComboBaseRaceIT extends AbstractPostgresIT {

    private static final Duration BLOCKED_DEADLINE = Duration.ofSeconds(5);

    @Autowired
    private AdminCatalogService admin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static ProductEditData edit(String name, String price) {
        return new ProductEditData(name, new BigDecimal(price), null);
    }

    /** Runs {@code call} on another thread and returns its outcome as a future. */
    private static <T> CompletableFuture<T> async(Supplier<T> call) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                result.complete(call.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /**
     * Runs {@code holder} in an open transaction (flushing it, so its row locks are real), starts {@code probe} on
     * another thread, proves Postgres reports the probe backend waiting on a lock, asserts the probe has not finished,
     * and commits the holder.
     */
    private <T> CompletableFuture<T> holdAndProbe(Runnable holder, Supplier<T> probe) {
        CompletableFuture<T> probed = new CompletableFuture<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            holder.run();
            entityManager.flush();
            async(probe).whenComplete((value, error) -> {
                if (error != null) {
                    probed.completeExceptionally(error);
                } else {
                    probed.complete(value);
                }
            });
            LockProbe.awaitBlocked(dataSource, BLOCKED_DEADLINE, "products");
            assertThat(probed.isDone()).as("the probe must be waiting for the product row the holder locks").isFalse();
        });
        return probed;
    }

    private static void assertInvalidLink(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.reason()).isEqualTo(ReasonCode.INVALID_COMBO_LINK));
    }

    private static Throwable failureOf(CompletableFuture<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        throw new AssertionError("the call was expected to fail but succeeded");
    }

    @Test
    void creatingAComboWaitsForAConcurrentDeactivationOfTheBaseAndThenIsRejected() throws Exception {
        long base = admin.createBase(product("Frasco", "100.00")).id();
        int products = count("SELECT count(*) FROM products");
        int auditRows = count("SELECT count(*) FROM admin_audit_log");

        CompletableFuture<?> created = holdAndProbe(() -> admin.deactivate(base),
                () -> admin.createCombo(product("Combo x2", "180.00"), base, 2));

        assertInvalidLink(failureOf(created));
        assertThat(count("SELECT count(*) FROM products")).as("no combo is created").isEqualTo(products);
        assertThat(count("SELECT count(*) FROM admin_audit_log")).as("only the deactivation is audited")
                .isEqualTo(auditRows + 1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'PRODUCT_CREATED'"))
                .as("only the base creation").isEqualTo(1);
    }

    @Test
    void activatingAComboWaitsForAConcurrentDeactivationOfItsBaseAndThenIsRejected() throws Exception {
        long base = admin.createBase(product("Frasco", "100.00")).id();
        long combo = admin.createCombo(product("Combo x2", "180.00"), base, 2).id();
        admin.deactivate(combo);
        int auditRows = count("SELECT count(*) FROM admin_audit_log");

        CompletableFuture<?> activated = holdAndProbe(() -> admin.deactivate(base), () -> admin.activate(combo));

        assertInvalidLink(failureOf(activated));
        assertThat(count("SELECT count(*) FROM products WHERE id = ? AND active", combo)).as("combo stays inactive")
                .isZero();
        assertThat(count("SELECT count(*) FROM admin_audit_log")).as("only the base deactivation is audited")
                .isEqualTo(auditRows + 1);
        assertThat(count("SELECT count(*) FROM admin_audit_log WHERE action = 'PRODUCT_ACTIVATED'")).isZero();
    }

    @Test
    void twoEditsFromTheSameVersionLeaveOnlyTheWinnerPersistedAndAudited() throws Exception {
        long id = admin.createBase(product("Frasco", "100.00")).id();
        long version = jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id);
        int auditRows = count("SELECT count(*) FROM admin_audit_log");

        CompletableFuture<?> loser = holdAndProbe(() -> admin.update(id, version, edit("Ganador", "150.00")),
                () -> admin.update(id, version, edit("Perdedor", "999.00")));

        assertThat(failureOf(loser)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(count("SELECT count(*) FROM products WHERE id = ? AND name = 'Ganador' AND price = 150.00"
                + " AND version = ?", id, version + 1)).as("the winner's edit is the persisted state").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE entity_id = ? AND action <> 'PRODUCT_CREATED'"
                + " ORDER BY id", String.class, id)).as("only the winner's rows").containsExactly("PRICE_CHANGED",
                "PRODUCT_UPDATED");
        assertThat(count("SELECT count(*) FROM admin_audit_log")).isEqualTo(auditRows + 2);
    }

    @Test
    void anEditAndADeactivationRacingOnTheSameRowLeaveOnlyTheWinnerPersistedAndAudited() throws Exception {
        long id = admin.createBase(product("Frasco", "100.00")).id();
        long version = jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id);
        int auditRows = count("SELECT count(*) FROM admin_audit_log");

        // The edit wins and holds the row; the deactivation reads the old state and must lose.
        CompletableFuture<?> loser = holdAndProbe(() -> admin.update(id, version, edit("Ganador", "150.00")),
                () -> admin.deactivate(id));

        assertThat(failureOf(loser)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(count("SELECT count(*) FROM products WHERE id = ? AND active AND name = 'Ganador' AND version = ?",
                id, version + 1)).as("the edit is persisted and the product is still active").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE entity_id = ? AND action <> 'PRODUCT_CREATED'"
                + " ORDER BY id", String.class, id)).as("no deactivation row for the loser")
                .containsExactly("PRICE_CHANGED", "PRODUCT_UPDATED");
        assertThat(count("SELECT count(*) FROM admin_audit_log")).isEqualTo(auditRows + 2);
    }

    @Test
    void aDeactivationAndAnEditRacingOnTheSameRowLeaveOnlyTheWinnerPersistedAndAudited() throws Exception {
        long id = admin.createBase(product("Frasco", "100.00")).id();
        long version = jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id);
        int auditRows = count("SELECT count(*) FROM admin_audit_log");

        // The deactivation wins and holds the row; the edit read the same version and must lose.
        CompletableFuture<?> loser = holdAndProbe(() -> admin.deactivate(id),
                () -> admin.update(id, version, edit("Perdedor", "999.00")));

        assertThat(failureOf(loser)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(count("SELECT count(*) FROM products WHERE id = ? AND NOT active AND name = 'Frasco' AND version = ?",
                id, version + 1)).as("the deactivation is persisted and the edit is not").isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE entity_id = ? AND action <> 'PRODUCT_CREATED'"
                + " ORDER BY id", String.class, id)).containsExactly("PRODUCT_DEACTIVATED");
        assertThat(count("SELECT count(*) FROM admin_audit_log")).isEqualTo(auditRows + 1);
    }
}
