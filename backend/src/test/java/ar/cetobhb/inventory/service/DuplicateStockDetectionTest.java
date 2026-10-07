package ar.cetobhb.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * createStock may only translate a unique/primary key violation into StockAlreadyConfiguredException. Other
 * integrity errors (CHECK 23514, FK 23503, ...) are not reachable through createStock (Java validates first), so
 * the classification is unit-tested on the exception shapes instead.
 */
class DuplicateStockDetectionTest {

    @Test
    void aUniqueViolationSqlStateIsADuplicate() {
        var e = new DataIntegrityViolationException("dup", new SQLException("dup", "23505"));
        assertThat(InventoryService.isDuplicateStock(e)).isTrue();
    }

    @Test
    void theStockPrimaryKeyConstraintNameIsADuplicate() {
        var cause = new ConstraintViolationException("dup", new SQLException("dup", "00000"), "inventory_stock_pk");
        assertThat(InventoryService.isDuplicateStock(new DataIntegrityViolationException("dup", cause))).isTrue();
    }

    @Test
    void aCheckViolationIsNotADuplicate() {
        var e = new DataIntegrityViolationException("check", new SQLException("check", "23514"));
        assertThat(InventoryService.isDuplicateStock(e)).isFalse();
    }

    @Test
    void aForeignKeyViolationIsNotADuplicate() {
        var cause = new ConstraintViolationException("fk", new SQLException("fk", "23503"), "inventory_stock_product_fk");
        assertThat(InventoryService.isDuplicateStock(new DataIntegrityViolationException("fk", cause))).isFalse();
    }

    @Test
    void anExceptionWithoutSqlCauseIsNotADuplicate() {
        assertThat(InventoryService.isDuplicateStock(new DataIntegrityViolationException("unknown"))).isFalse();
    }
}
