package archfixtures.ar.cetobhb.crossorigin.controller;

import org.springframework.web.bind.annotation.CrossOrigin;

/** Violation: class-level and method-level {@code @CrossOrigin}. */
@CrossOrigin("*")
public class CrossOriginController {

    @CrossOrigin(origins = "https://evil.example")
    public String open() {
        return "open";
    }
}
