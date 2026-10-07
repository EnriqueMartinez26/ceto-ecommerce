package archfixtures.ar.cetobhb.tx.web;

import org.springframework.transaction.annotation.Transactional;

/** Deliberate violation: transaction boundary outside a service package (class and method level). */
@Transactional
public class TxOutsideService {

    @Transactional
    public void run() {
    }
}
