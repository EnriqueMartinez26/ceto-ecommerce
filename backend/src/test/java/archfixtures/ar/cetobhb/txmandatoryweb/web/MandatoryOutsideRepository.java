package archfixtures.ar.cetobhb.txmandatoryweb.web;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deliberate violation: the MANDATORY exemption is for repositories only, not for any other package. */
public class MandatoryOutsideRepository {

    @Transactional(propagation = Propagation.MANDATORY)
    public void mandatoryInTheWrongLayer() {
    }
}
