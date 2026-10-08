package ar.cetobhb.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AINV-06 hardening: the admin read paths reject unbounded inputs before they reach the database. */
class InventoryServiceBoundsTest {

    private final InventoryStockRepository stock = mock(InventoryStockRepository.class);
    private final InventoryService inventory =
            new InventoryService(stock, mock(CatalogService.class), Clock.systemUTC());

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, InventoryService.MAX_MISMATCH_LIMIT + 1, Integer.MAX_VALUE})
    void reservationMismatchesRejectsALimitOutsideOneToTheCap(int limit) {
        assertThatThrownBy(() -> inventory.reservationMismatches(limit))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(stock);
    }

    @Test
    void reservationMismatchesAcceptsTheBoundaryLimits() {
        when(stock.findReservationMismatches(1)).thenReturn(List.of());
        when(stock.findReservationMismatches(InventoryService.MAX_MISMATCH_LIMIT)).thenReturn(List.of());

        assertThat(inventory.reservationMismatches(1)).isEmpty();
        assertThat(inventory.reservationMismatches(InventoryService.MAX_MISMATCH_LIMIT)).isEmpty();
        verify(stock).findReservationMismatches(InventoryService.MAX_MISMATCH_LIMIT);
    }

    @Test
    void levelsRejectsANullCollection() {
        assertThatThrownBy(() -> inventory.levels(null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(stock);
    }

    @Test
    void levelsRejectsAnOversizedIdCollection() {
        Collection<Long> tooMany = new ArrayList<>(
                LongStream.rangeClosed(1, InventoryService.MAX_LEVEL_IDS + 1L).boxed().toList());

        assertThatThrownBy(() -> inventory.levels(tooMany)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(stock);
    }

    @Test
    void levelsAcceptsExactlyTheCap() {
        Collection<Long> atCap = LongStream.rangeClosed(1, InventoryService.MAX_LEVEL_IDS).boxed().toList();
        when(stock.findLevels(atCap)).thenReturn(List.of());

        assertThat(inventory.levels(atCap)).isEmpty();
        verify(stock).findLevels(atCap);
    }
}
