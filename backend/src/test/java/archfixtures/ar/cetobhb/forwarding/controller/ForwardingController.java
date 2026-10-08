package archfixtures.ar.cetobhb.forwarding.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Violations: an internal forward and include through the dispatcher, and a plain {@code @Controller} (view names). */
@Controller
public class ForwardingController {

    @GetMapping("/forward-call")
    public void forwardCall(HttpServletRequest request, HttpServletResponse response) throws Exception {
        request.getRequestDispatcher("/api/admin/x").forward(request, response);
    }

    @GetMapping("/include-call")
    public void includeCall(HttpServletRequest request, HttpServletResponse response) throws Exception {
        RequestDispatcher dispatcher = request.getRequestDispatcher("/api/admin/x");
        dispatcher.include(request, response);
    }

    /** In a non-@ResponseBody controller this String is a view name: "forward:" would be honoured. */
    @GetMapping("/forward-view")
    public String forwardViewName() {
        return "forward:/api/admin/x";
    }
}
