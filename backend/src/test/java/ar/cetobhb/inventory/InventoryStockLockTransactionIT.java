package ar.cetobhb.inventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.inventory.repository.InventoryStockRepository;
import ar.cetobhb.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/** A row lock only means something inside a transaction: calling the lock query without one must fail loudly. */
class InventoryStockLockTransactionIT extends AbstractPostgresIT {

    @Autowired
    private InventoryStockRepository stock;

    @Test
    void lockByProductIdWithoutATransactionIsRejected() {
        assertThatThrownBy(() -> stock.lockByProductId(1L)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
