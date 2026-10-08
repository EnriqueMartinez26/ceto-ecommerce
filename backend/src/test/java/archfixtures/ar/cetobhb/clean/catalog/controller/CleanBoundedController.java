package archfixtures.ar.cetobhb.clean.catalog.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliant control: every client-controlled input of every handler is bounded, including nested body members,
 * top-level containers, optionals, arrays and model objects, and every body, model object, nested DTO and collection of
 * DTOs is {@code @Valid}.
 */
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

    @PostMapping("/many")
    public String createMany(@RequestBody @Valid @Size(max = 10) List<Line> lines) {
        return String.valueOf(lines.size());
    }

    @GetMapping("/optional")
    public String optional(@RequestParam @Min(1) @Max(9) Optional<Integer> page,
            @RequestParam @Size(max = 8) String[] tags) {
        return String.valueOf(page.orElse(1)) + tags.length;
    }

    @GetMapping("/model")
    public String model(@ModelAttribute @Valid Filter filter) {
        return filter.query();
    }

    public record Body(@Size(max = 40) String name, @Valid @Size(max = 10) List<Line> lines, Kind kind) {
    }

    public record Line(@Min(1) @Max(100) Integer quantity) {
    }

    public record Filter(@Size(max = 40) String query, @Valid Line line) {
    }

    /** An enum with a field: a constant set, not an input. */
    public enum Kind {
        SMALL("s"), LARGE("l");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }
    }
}
