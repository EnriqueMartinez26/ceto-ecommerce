package archfixtures.ar.cetobhb.clean.admin.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Compliant control: an admin controller whose every path, type-level and method-level, is under /api/admin/. */
@RestController
@RequestMapping("/api/admin/clean")
public class CleanAdminController {

    @GetMapping("/{id}")
    public String byId(@PathVariable @Min(1) @Max(1000) long id) {
        return String.valueOf(id);
    }

    @PostMapping(path = {"/pay", "/dispatch"})
    public String actions() {
        return "ok";
    }
}
