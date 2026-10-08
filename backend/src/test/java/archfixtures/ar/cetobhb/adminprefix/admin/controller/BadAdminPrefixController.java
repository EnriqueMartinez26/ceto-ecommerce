package archfixtures.ar.cetobhb.adminprefix.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violations: admin handlers whose method-level path is outside {@code /api/admin/}. One compliant handler as control. */
@RestController
public class BadAdminPrefixController {

    /** Violation: "admins" is not "admin". */
    @GetMapping("/api/admins/x")
    public String plural() {
        return "plural";
    }

    /** Violation: the admin prefix without the api segment. */
    @GetMapping("/admin/x")
    public String missingApiSegment() {
        return "missing";
    }

    /** Violation: no path at all maps the type's root, which is not under the admin prefix. */
    @PostMapping
    public String noPathAtAll() {
        return "none";
    }

    /** Compliant control. */
    @GetMapping("/api/admin/fine")
    public String fine() {
        return "fine";
    }
}
