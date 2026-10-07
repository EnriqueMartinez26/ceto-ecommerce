package archfixtures.ar.cetobhb.clean.catalog.service;

import archfixtures.ar.cetobhb.clean.catalog.repository.CleanCatalogRepository;

/** Compliant control: a service using its own feature's repository. */
public class CleanCatalogService {

    CleanCatalogRepository repository() {
        return new CleanCatalogRepository();
    }
}
