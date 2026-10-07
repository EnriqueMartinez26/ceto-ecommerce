package archfixtures.ar.cetobhb.clean.catalog.controller;

import archfixtures.ar.cetobhb.clean.catalog.service.CleanCatalogService;

/** Compliant control: a controller that talks to its feature's service only and never to a repository. */
public class CleanCatalogController {

    private final CleanCatalogService service = new CleanCatalogService();

    CleanCatalogService service() {
        return service;
    }
}
