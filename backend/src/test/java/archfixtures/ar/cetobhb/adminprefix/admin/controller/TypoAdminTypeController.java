package archfixtures.ar.cetobhb.adminprefix.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violation: a typo in the class-level prefix, so every method path combines into {@code /api/admn/...}. */
@RestController
@RequestMapping("/api/admn")
public class TypoAdminTypeController {

    @GetMapping("/orders")
    public String typoAtTypeLevel() {
        return "typo";
    }
}
