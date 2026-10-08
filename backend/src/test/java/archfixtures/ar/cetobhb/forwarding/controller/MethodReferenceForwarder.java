package archfixtures.ar.cetobhb.forwarding.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violation: {@code forward} and {@code include} used as method references, which a plain call check would miss. */
@RestController
public class MethodReferenceForwarder {

    /** A functional interface with the shape of {@code RequestDispatcher.forward}. */
    interface Dispatch {
        void go(ServletRequest request, ServletResponse response) throws Exception;
    }

    @GetMapping("/forward-reference")
    public Dispatch forwardReference(RequestDispatcher dispatcher) {
        return dispatcher::forward;
    }

    @GetMapping("/include-reference")
    public Dispatch includeReference(RequestDispatcher dispatcher) {
        return dispatcher::include;
    }
}
