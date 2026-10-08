package ar.cetobhb.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The configured caps reach the reads: the facade never asks for more rows than the validated settings allow. */
class AdminInventoryServiceCapsTest {

    private final CatalogService catalog = mock(CatalogService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final AdminInventoryService admin = new AdminInventoryService(inventory, catalog,
            mock(AdminAuditService.class), new AdminProperties(37, 11));

    @Test
    void theStockViewAsksTheCatalogForAtMostTheConfiguredProductCap() {
        when(catalog.listAll(37)).thenReturn(List.of());

        assertThat(admin.stockView()).isEmpty();

        verify(catalog).listAll(37);
        verifyNoInteractions(inventory);
    }
}
