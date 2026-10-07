package archfixtures.ar.cetobhb.txmandatory.repository;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Compliant: a repository method may require a transaction (MANDATORY) but never open one. */
public class MandatoryRepository {

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSomething() {
    }
}
