package archfixtures.ar.cetobhb.txclass.web;

import org.springframework.transaction.annotation.Transactional;

/** Deliberate violation: class-level @Transactional outside a service package; no annotated methods. */
@Transactional
public class TxClassOnly {

    public void plain() {
    }
}
