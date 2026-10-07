package archfixtures.ar.cetobhb.txclass.service;

import org.springframework.transaction.annotation.Transactional;

/**
 * Compliant bystander: a @Transactional method inside a service package. It keeps the method rule non-empty
 * so {@code TxClassOnly} is the only reason the class-level rule fails.
 */
public class TxCompliantService {

    @Transactional
    public void run() {
    }
}
