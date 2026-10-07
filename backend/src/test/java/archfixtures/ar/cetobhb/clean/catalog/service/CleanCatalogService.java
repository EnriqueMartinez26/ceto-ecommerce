package archfixtures.ar.cetobhb.clean.catalog.service;

import archfixtures.ar.cetobhb.clean.catalog.repository.CleanCatalogRepository;
import org.springframework.transaction.annotation.Transactional;

/** Compliant control: a service owning the transaction and using its own feature's repository. */
public class CleanCatalogService {

    @Transactional
    public CleanCatalogRepository repository() {
        return new CleanCatalogRepository();
    }
}
