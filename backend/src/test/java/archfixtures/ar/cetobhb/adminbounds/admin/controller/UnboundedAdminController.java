package archfixtures.ar.cetobhb.adminbounds.admin.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Violations in an admin controller that sits correctly under /api/admin/: the prefix rule is satisfied, the bounds
 * rule must still fail on each handler (a page size without a maximum, an unsized search text and status list, an
 * after cursor with one bound only, and a request body that is not validated).
 */
@RestController
@RequestMapping("/api/admin/unbounded")
public class UnboundedAdminController {

    @GetMapping("/size")
    public String size(@RequestParam(defaultValue = "20") Integer pageSizeNoMax) {
        return "x";
    }

    @GetMapping("/text")
    public String text(@RequestParam(required = false) String searchTextNoSize) {
        return "x";
    }

    @GetMapping("/list")
    public String list(@RequestParam List<String> statusesNoSize) {
        return "x";
    }

    @GetMapping("/after")
    public String after(@RequestParam(required = false) @jakarta.validation.constraints.Min(1) Long afterNoMax) {
        return "x";
    }

    @PostMapping("/body")
    public String body(@RequestBody AdminBody unvalidatedAdminBody) {
        return "x";
    }

    /** A body with a bounded member: only the missing @Valid on the parameter is wrong. */
    public record AdminBody(@jakarta.validation.constraints.Size(max = 10) String label) {
    }
}
