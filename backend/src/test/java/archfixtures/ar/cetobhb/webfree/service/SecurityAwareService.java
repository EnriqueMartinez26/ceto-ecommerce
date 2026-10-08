package archfixtures.ar.cetobhb.webfree.service;

import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Violation: a service that reads the logged-in principal. Who is allowed is the controller's and the filter chain's
 * business; a service that asks the security context hides an authorization decision in the middle of the core.
 */
public class SecurityAwareService {

    /** A direct call. */
    public String currentName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    /** A method reference, which ArchUnit's call-based predicates do not see as a call. */
    public Supplier<SecurityContext> contextSource() {
        return SecurityContextHolder::getContext;
    }
}
