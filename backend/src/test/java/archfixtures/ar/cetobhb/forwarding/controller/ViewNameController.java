package archfixtures.ar.cetobhb.forwarding.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

/** Violation: a {@code @RestController} that builds a view and so can ask for a "forward:" view name. */
@RestController
public class ViewNameController {

    @GetMapping("/forward-model-and-view")
    public ModelAndView forwardModelAndView() {
        return new ModelAndView("forward:/api/admin/x");
    }
}
