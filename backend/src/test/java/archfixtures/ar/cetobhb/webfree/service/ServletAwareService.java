package archfixtures.ar.cetobhb.webfree.service;

import jakarta.servlet.http.HttpServletRequest;

/** Violation: a service that touches the servlet API. */
public class ServletAwareService {

    public String clientOf(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
