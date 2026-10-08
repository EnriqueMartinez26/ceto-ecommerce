package archfixtures.ar.cetobhb.webexempt.web.service;

import jakarta.servlet.http.HttpServletRequest;

/** Not a feature layer: a "service" package under a web package (like common.web.service) may use the servlet API. */
public class WebHelper {

    public String clientOf(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
