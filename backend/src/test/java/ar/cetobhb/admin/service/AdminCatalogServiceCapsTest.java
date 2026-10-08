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

/** The configured product cap reaches the catalog read: the facade never asks for more rows than allowed. */
class AdminCatalogServiceCapsTest {

    @Test
    void theListAsksTheCatalogForAtMostTheConfiguredProductCap() {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.listAll(37)).thenReturn(List.of());
        AdminCatalogService admin = new AdminCatalogService(catalog, mock(InventoryService.class),
                mock(AdminAuditService.class), new AdminProperties(37, 11));

        assertThat(admin.list()).isEmpty();

        verify(catalog).listAll(37);
    }
}
