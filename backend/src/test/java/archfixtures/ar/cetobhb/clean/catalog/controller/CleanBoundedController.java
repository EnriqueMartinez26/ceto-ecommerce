package archfixtures.ar.cetobhb.clean.catalog.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Compliant control: every client-controlled input of every handler is bounded, including nested body members. */
@RestController
@RequestMapping("/clean")
public class CleanBoundedController {

    @GetMapping("/{id}")
    public String byId(@PathVariable @Min(1) @Max(1000) long id, @RequestHeader("X-Key") @Size(max = 64) String key) {
        return key + id;
    }

    @GetMapping("/positive/{id}")
    public String positive(@PathVariable @Positive @Max(1000) Long id) {
        return String.valueOf(id);
    }

    @PostMapping
    public String create(@RequestBody @Valid Body body) {
        return body.name();
    }

    public record Body(@Size(max = 40) String name, @Size(max = 10) List<@Valid Line> lines) {
    }

    public record Line(@Min(1) @Max(100) Integer quantity) {
    }
}
