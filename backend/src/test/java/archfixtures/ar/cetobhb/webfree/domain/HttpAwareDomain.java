package archfixtures.ar.cetobhb.webfree.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Violation: domain classes that know HTTP statuses and Spring web exceptions. */
public class HttpAwareDomain {

    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }

    public RuntimeException failure() {
        return new ResponseStatusException(HttpStatus.CONFLICT);
    }
}
