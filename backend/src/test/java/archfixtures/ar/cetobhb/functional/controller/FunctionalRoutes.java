package archfixtures.ar.cetobhb.functional.controller;

import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Violation: a functional endpoint, which no annotation-based inventory or input rule would ever see. */
public class FunctionalRoutes {

    public RouterFunction<ServerResponse> hidden() {
        return RouterFunctions.route().GET("/hidden", request -> ServerResponse.ok().build()).build();
    }
}
