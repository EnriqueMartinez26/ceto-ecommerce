package archfixtures.ar.cetobhb.webfree.domain;

import org.springframework.security.core.userdetails.UserDetails;

/** Violation: a domain type that knows the security framework's user object. */
public class SecurityAwareDomain {

    private UserDetails actor;

    public UserDetails actor() {
        return actor;
    }
}
