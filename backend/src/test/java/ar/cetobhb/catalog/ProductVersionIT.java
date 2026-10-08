package ar.cetobhb.catalog;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.catalog.repository.Product;
import ar.cetobhb.catalog.repository.ProductRepository;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ACAT-04 / CAT-A1 primitive: V5 adds products.version and the entity uses it as an optimistic lock. */
class ProductVersionIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogService catalog;

    @Autowired
    private ProductRepository products;

    @Autowired
    private PlatformTransactionManager txManager;

    private long versionOf(long id) {
        return jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id);
    }

    @Test
    void v5IsAppliedAndTheColumnIsNotNullWithDefaultZero() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success", Integer.class))
                .isEqualTo(1);

        Map<String, Object> column = jdbc.queryForMap("""
                SELECT is_nullable, column_default, data_type FROM information_schema.columns
                WHERE table_name = 'products' AND column_name = 'version'""");

        assertThat(column).containsEntry("is_nullable", "NO").containsEntry("data_type", "bigint");
        assertThat(String.valueOf(column.get("column_default"))).startsWith("0");
    }

    @Test
    void aRowInsertedWithoutAVersionStartsAtZero() {
        jdbc.update("INSERT INTO products (name, price) VALUES ('Raw', 10.00)");
        long id = jdbc.queryForObject("SELECT id FROM products WHERE name = 'Raw'", Long.class);

        assertThat(versionOf(id)).isZero();
        assertThat(products.findById(id).orElseThrow().version()).isZero();
    }

    @Test
    void anUpdateBumpsTheVersion() {
        long id = catalog.createBase(product("Base", "10.00")).id();
        assertThat(versionOf(id)).isZero();

        catalog.deactivate(id);

        assertThat(versionOf(id)).isEqualTo(1);
    }

    @Test
    void aStaleDetachedCopyCannotOverwriteANewerRow() {
        long id = catalog.createBase(product("Base", "10.00")).id();
        Product stale = new TransactionTemplate(txManager).execute(status -> products.findById(id).orElseThrow());
        catalog.deactivate(id);

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(
                status -> products.saveAndFlush(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(versionOf(id)).isEqualTo(1);
    }
}
