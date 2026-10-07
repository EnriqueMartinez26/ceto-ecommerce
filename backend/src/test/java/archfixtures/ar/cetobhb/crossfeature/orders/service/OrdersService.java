package archfixtures.ar.cetobhb.crossfeature.orders.service;

import archfixtures.ar.cetobhb.crossfeature.catalog.repository.CatalogRepository;

/** Deliberate violation: a service reaching into another feature's repository (AD-2). */
public class OrdersService {

    CatalogRepository foreignRepository() {
        return new CatalogRepository();
    }
}
