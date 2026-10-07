package archfixtures.ar.cetobhb.crossfeature.catalog.service;

import archfixtures.ar.cetobhb.crossfeature.catalog.repository.CatalogRepository;

/** Allowed access: a feature's service using its own repository. */
public class CatalogOwnService {

    CatalogRepository repository() {
        return new CatalogRepository();
    }
}
