package ar.cetobhb.orders.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExpiryResultTest {

    @Test
    void countsExpiredFailedAndProcessedOrders() {
        ExpiryResult result = new ExpiryResult(List.of(1L, 2L, 3L), List.of(4L, 5L));

        assertThat(result.expired()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.processed()).isEqualTo(5);
    }

    @Test
    void anEmptyResultProcessedNothing() {
        ExpiryResult result = new ExpiryResult(List.of(), List.of());

        assertThat(result.expired()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.processed()).isZero();
    }

    @Test
    void failedAloneCountsAsProcessed() {
        ExpiryResult result = new ExpiryResult(List.of(), List.of(9L));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.processed()).isEqualTo(1);
    }
}
