package ar.cetobhb.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.cetobhb.admin.config.AdminPropertiesFixtures;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The configured caps reach the reads: the facade asks for exactly one row more than the cap (to detect truncation) and never more. */
class AdminInventoryServiceCapsTest {

    private final CatalogService catalog = mock(CatalogService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final AdminInventoryService admin = new AdminInventoryService(inventory, catalog,
            mock(AdminAuditService.class), AdminPropertiesFixtures.withCaps(37, 11));

    @Test
    void theStockViewAsksTheCatalogForTheConfiguredProductCapPlusOne() {
        when(catalog.listAll(38)).thenReturn(List.of());

        assertThat(admin.stockView().rows()).isEmpty();

        verify(catalog).listAll(38);
        verifyNoInteractions(inventory);
    }

    @Test
    void theReconciliationAsksForTheConfiguredRowCapPlusOne() {
        when(inventory.reservationMismatches(12)).thenReturn(List.of());

        assertThat(admin.reconciliation().rows()).isEmpty();

        verify(inventory).reservationMismatches(12);
        verifyNoInteractions(catalog);
    }
}
