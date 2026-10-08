package archfixtures.ar.cetobhb.adminprefix.stray.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violations: a controller outside {@code ..admin.controller..} that claims paths under {@code /api/admin}. */
@RestController
public class StrayAdminPathController {

    @GetMapping("/api/admin/leak")
    public String adminPathInAPublicPackage() {
        return "leak";
    }

    @RestController
    @RequestMapping("/API/Admin")
    public static class OtherCase {

        @GetMapping("/leak-by-case")
        public String adminPathByCase() {
            return "case";
        }
    }

    /** Compliant control: an ordinary public path. */
    @GetMapping("/api/store/fine")
    public String publicPath() {
        return "fine";
    }
}
