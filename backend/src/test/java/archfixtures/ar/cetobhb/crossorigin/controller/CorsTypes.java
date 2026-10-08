package archfixtures.ar.cetobhb.crossorigin.controller;

import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistration;

/** Violation: holds the CORS filter and a CORS registration, which the rule must name explicitly. */
public class CorsTypes {

    private CorsFilter filter;
    private CorsRegistration registration;

    public boolean configured() {
        return filter != null && registration != null;
    }
}
