package archfixtures.ar.cetobhb.clean.admin.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Compliant control: the parameter shapes of a paged admin list, every input bounded on both sides. */
@RestController
@RequestMapping("/api/admin/clean-orders")
public class CleanAdminOrdersController {

    @GetMapping
    public String list(
            @RequestParam(defaultValue = "all") @Size(max = 16) @Pattern(regexp = "all|PAGADO") String status,
            @RequestParam(required = false) @Size(max = 64) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) @Min(1) @Max(9_007_199_254_740_991L) Long after) {
        return "x";
    }
}
