package archfixtures.ar.cetobhb.inventory.service;

import ar.cetobhb.inventory.service.InventoryService;

/** Fixture for AbsoluteAdjustNotReachableTest: a class living in an {@code ..inventory.service..} package. */
public class SiblingServiceCaller {

    void call(InventoryService inventory) {
        inventory.adjust(1L, 5);
    }
}
