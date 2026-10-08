package archfixtures.ar.cetobhb.webfree.service;

import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;

/** Violation: a service that knows Boot's web server and Boot's MVC error endpoint. */
public class BootWebAwareService {

    private WebServer server;
    private BasicErrorController errors;

    public boolean ready() {
        return server != null && errors != null;
    }
}
