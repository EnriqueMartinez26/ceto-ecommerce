package ar.cetobhb.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.repository.InventoryStockRepository;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryServiceAvailabilityTest {

    @Test
    void anEmptyRequestNeverReachesTheRepository() {
        InventoryStockRepository stock = mock(InventoryStockRepository.class);
        InventoryService inventory = new InventoryService(stock, mock(CatalogService.class), Clock.systemUTC());

        assertThat(inventory.availableByBase(List.of())).isEmpty();

        verifyNoInteractions(stock);
    }
}
