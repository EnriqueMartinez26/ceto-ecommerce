package ar.cetobhb.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.inventory.service.InventoryService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The configured product cap reaches the catalog read: the facade asks for exactly one row more than the cap, to detect truncation. */
class AdminCatalogServiceCapsTest {

    @Test
    void theListAsksTheCatalogForTheConfiguredProductCapPlusOne() {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.listAll(38)).thenReturn(List.of());
        AdminCatalogService admin = new AdminCatalogService(catalog, mock(InventoryService.class),
                mock(AdminAuditService.class), new AdminProperties(37, 11));

        assertThat(admin.list().rows()).isEmpty();

        verify(catalog).listAll(38);
    }
}
