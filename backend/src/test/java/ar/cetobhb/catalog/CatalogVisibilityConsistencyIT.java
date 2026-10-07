package ar.cetobhb.catalog;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.ProductView;
import ar.cetobhb.catalog.service.CatalogService;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The storefront must never show what checkout rejects, nor hide what checkout accepts: the two visibility rules
 * (the JPQL in the repository and the in-memory rule of findForOrder) have to agree on every id.
 */
class CatalogVisibilityConsistencyIT extends AbstractPostgresIT {

    private static final long UNKNOWN_ID = 999_999L;

    @Autowired
    private CatalogService catalog;

    private boolean checkoutAccepts(long id) {
        try {
            catalog.findForOrder(List.of(id));
            return true;
        } catch (ProductNotAvailableException e) {
            return false;
        }
    }

    private boolean storefrontShows(long id) {
        try {
            catalog.findVisible(id);
            return true;
        } catch (ProductNotFoundException e) {
            return false;
        }
    }

    @Test
    void theStorefrontAndCheckoutAcceptExactlyTheSameIds() {
        long activeBase = catalog.createBase(product("Active base", "100.00")).id();
        long inactiveBase = catalog.createBase(product("Inactive base", "100.00")).id();
        long comboOverActive = catalog.createCombo(product("Combo A", "270.00"), activeBase, 3).id();
        long comboOverInactive = catalog.createCombo(product("Combo B", "270.00"), inactiveBase, 3).id();
        long inactiveCombo = catalog.createCombo(product("Combo C", "270.00"), activeBase, 3).id();
        catalog.deactivate(inactiveBase);
        catalog.deactivate(inactiveCombo);
        List<Long> ids = List.of(
                activeBase, inactiveBase, comboOverActive, comboOverInactive, inactiveCombo, UNKNOWN_ID);

        Set<Long> acceptedByCheckout = new LinkedHashSet<>();
        Set<Long> shownByStorefront = new LinkedHashSet<>();
        for (long id : ids) {
            if (checkoutAccepts(id)) {
                acceptedByCheckout.add(id);
            }
            if (storefrontShows(id)) {
                shownByStorefront.add(id);
            }
        }
        Set<Long> listed = new LinkedHashSet<>(
                catalog.listVisible(10).stream().map(ProductView::id).toList());

        assertThat(acceptedByCheckout).containsExactlyInAnyOrder(activeBase, comboOverActive);
        assertThat(shownByStorefront).isEqualTo(acceptedByCheckout);
        assertThat(listed).isEqualTo(acceptedByCheckout);
    }
}
