package archfixtures.ar.cetobhb.txrepo.repository;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deliberate violation: repository @Transactional whose propagation is not MANDATORY. */
public class NonMandatoryRepository {

    @Transactional
    public void defaultPropagation() {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requiresNew() {
    }
}
