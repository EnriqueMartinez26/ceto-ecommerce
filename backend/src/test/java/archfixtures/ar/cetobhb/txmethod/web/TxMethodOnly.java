package archfixtures.ar.cetobhb.txmethod.web;

import org.springframework.transaction.annotation.Transactional;

/** Deliberate violation: method-level @Transactional outside a service package; the class is not annotated. */
public class TxMethodOnly {

    @Transactional
    public void boundaryMethod() {
    }
}
