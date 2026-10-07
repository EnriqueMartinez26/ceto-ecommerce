package archfixtures.ar.cetobhb.jtx.service;

import jakarta.transaction.Transactional;

/** Deliberate violation: jakarta.transaction.Transactional on a class, even inside a service package. */
@Transactional
public class JakartaTxClassLevel {
}
