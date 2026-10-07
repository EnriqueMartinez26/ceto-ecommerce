package archfixtures.ar.cetobhb.jtx.service;

import jakarta.transaction.Transactional;

/** Deliberate violation: jakarta.transaction.Transactional on a method, even inside a service package. */
public class JakartaTxMethodLevel {

    @Transactional
    public void run() {
    }
}
