package archfixtures.ar.cetobhb.unbounded.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Violations: inputs that hide from a rule which only looks at simple parameters and the fields of a body class:
 * top-level body containers, arrays, maps, optionals, model objects, and DTOs reachable only without {@code @Valid}.
 */
public class UncheckedInputsController {

    @PostMapping("/top-level-list")
    public String topLevelListInput(@RequestBody @Valid List<Item> items) {
        return "x";
    }

    @PostMapping("/top-level-map")
    public String topLevelMapInput(@RequestBody @Valid Map<String, String> entries) {
        return "x";
    }

    @PostMapping("/raw-string")
    public String rawStringInput(@RequestBody @Valid String text) {
        return "x";
    }

    @PostMapping("/raw-long")
    public String rawLongInput(@RequestBody @Valid Long number) {
        return "x";
    }

    @GetMapping("/array")
    public String arrayInput(@RequestParam String[] tags) {
        return "x";
    }

    @GetMapping("/map")
    public String mapInput(@RequestParam Map<String, String> filters) {
        return "x";
    }

    @GetMapping("/optional-number")
    public String optionalNumberInput(@RequestParam Optional<Long> optionalNumber) {
        return "x";
    }

    @GetMapping("/optional-text")
    public String optionalTextInput(@RequestParam Optional<String> optionalText) {
        return "x";
    }

    @GetMapping("/model")
    public String modelAttributeInput(@ModelAttribute @Valid ModelInput modelInput) {
        return "x";
    }

    @GetMapping("/plain")
    public String plainObjectInput(@Valid PlainInput plainInput) {
        return "x";
    }

    @PostMapping("/unvalidated-body")
    public String unvalidatedBodyInput(@RequestBody Item item) {
        return "x";
    }

    @GetMapping("/unvalidated-model")
    public String unvalidatedModelInput(Item item) {
        return "x";
    }

    @PostMapping("/nested")
    public String nestedWithoutValid(@RequestBody @Valid Holder holder) {
        return "x";
    }

    public record Item(@Min(1) @Max(5) Integer count, @Size(max = 5) String label) {
    }

    public record ModelInput(String modelName) {
    }

    public static class PlainInput {
        private int plainCount;

        public int getPlainCount() {
            return plainCount;
        }

        public void setPlainCount(int plainCount) {
            this.plainCount = plainCount;
        }
    }

    /** An enum with a field is a constant set, not an input: it must never be reported. */
    public enum Kind {
        SMALL("s"), LARGE("l");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }
    }

    public record Holder(Item unvalidatedMember, @Size(max = 3) List<Item> unvalidatedMembers,
            @Valid Item validatedMember, @Valid @Size(max = 3) List<Item> validatedMembers, Kind kind) {
    }
}
