package archfixtures.ar.cetobhb.webtypes.web;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Violation: web types outside a controller package. */
public class MisplacedRestController {

    @RestController
    public static class MisplacedRestControllerType {
    }

    @RestControllerAdvice
    public static class MisplacedAdviceType {
    }
}
