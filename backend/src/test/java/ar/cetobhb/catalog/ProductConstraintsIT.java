package ar.cetobhb.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.AbstractPostgresIT;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every CHECK/FK of V1 is violated with raw SQL so the database, not Java validation, rejects the row. */
class ProductConstraintsIT extends AbstractPostgresIT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FK_VIOLATION = "23503";

    @Autowired
    private JdbcTemplate jdbc;

    private long insertBase() {
        return jdbc.queryForObject(
                "INSERT INTO products (name, price) VALUES ('base', 100.00) RETURNING id", Long.class);
    }

    private void assertRejected(String sql, String sqlState, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(root).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) root).getSQLState()).isEqualTo(sqlState);
                    assertThat(root.getMessage()).contains("\"" + constraint + "\"");
                });
    }

    @Test
    void priceMustBePositive() {
        assertRejected("INSERT INTO products (name, price) VALUES ('p', 0)", CHECK_VIOLATION, "products_price_ck");
        assertRejected("INSERT INTO products (name, price) VALUES ('p', -1.00)", CHECK_VIOLATION,
                "products_price_ck");
    }

    @Test
    void compareAtMustBeStrictlyGreaterThanPrice() {
        assertRejected("INSERT INTO products (name, price, compare_at_price) VALUES ('p', 100, 100)",
                CHECK_VIOLATION, "products_compare_at_ck");
        assertRejected("INSERT INTO products (name, price, compare_at_price) VALUES ('p', 100, 90)",
                CHECK_VIOLATION, "products_compare_at_ck");
        assertThatCode(() -> {
            jdbc.update("INSERT INTO products (name, price) VALUES ('no compare', 100)");
            jdbc.update("INSERT INTO products (name, price, compare_at_price) VALUES ('greater', 100, 100.01)");
        }).doesNotThrowAnyException();
    }

    @Test
    void comboLinkIsBothOrNeither() {
        long base = insertBase();
        assertRejected(
                "INSERT INTO products (name, price, combo_base_units) VALUES ('c', 100, 2)",
                CHECK_VIOLATION, "products_combo_pair_ck");
        assertRejected(
                "INSERT INTO products (name, price, combo_base_product_id) VALUES ('c', 100, ?)",
                CHECK_VIOLATION, "products_combo_pair_ck", base);
    }

    @Test
    void comboUnitsMustBeAtLeastTwo() {
        long base = insertBase();
        for (int units : new int[] {0, -1, 1}) {
            assertRejected(
                    "INSERT INTO products (name, price, combo_base_product_id, combo_base_units) "
                            + "VALUES ('c', 100, ?, ?)",
                    CHECK_VIOLATION, "products_combo_units_ck", base, units);
        }
        assertThatCode(() -> jdbc.update(
                "INSERT INTO products (name, price, combo_base_product_id, combo_base_units) "
                        + "VALUES ('c', 100, ?, 2)", base)).doesNotThrowAnyException();
    }

    @Test
    void comboCannotReferenceItself() {
        assertRejected(
                "INSERT INTO products (id, name, price, combo_base_product_id, combo_base_units) "
                        + "OVERRIDING SYSTEM VALUE VALUES (500, 'c', 100, 500, 2)",
                CHECK_VIOLATION, "products_combo_not_self_ck");
    }

    @Test
    void comboMustReferenceAnExistingProduct() {
        assertRejected(
                "INSERT INTO products (name, price, combo_base_product_id, combo_base_units) "
                        + "VALUES ('c', 100, 9999, 2)",
                FK_VIOLATION, "products_combo_base_fk");
    }

    @Test
    void freshlyMigratedDatabaseHasNoProducts() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isZero();
    }
}
