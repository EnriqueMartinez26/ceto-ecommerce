package archfixtures.ar.cetobhb.unbounded.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** Violations: one handler per way of leaving a client-controlled input unbounded. */
public class UnboundedController {

    @GetMapping("/no-bounds/{id}")
    public String noBounds(@PathVariable Long id) {
        return "x";
    }

    @GetMapping("/only-min/{id}")
    public String onlyMin(@PathVariable @Min(1) long onlyMinId) {
        return "x";
    }

    @GetMapping("/only-max/{id}")
    public String onlyMax(@PathVariable @Max(10) int onlyMaxId) {
        return "x";
    }

    @GetMapping("/query")
    public String query(@RequestParam String unsizedQuery, java.math.BigDecimal unannotatedAmount) {
        return "x";
    }

    @GetMapping("/header")
    public String header(@RequestHeader("X-Key") String unsizedHeader) {
        return "x";
    }

    @GetMapping("/header-size-without-max")
    public String headerSizeWithoutMax(@RequestHeader("X-Other") @Size(min = 1) String minOnlyHeader) {
        return "x";
    }

    @PostMapping("/body")
    public String body(@RequestBody UnboundedBody body) {
        return "x";
    }

    public record UnboundedBody(Integer unboundedQuantity, String unsizedText, List<Nested> unsizedList,
            @Size(max = 5) List<Nested> sizedList) {
    }

    public record Nested(@Min(0) @Max(9) int boundedInNested, java.math.BigDecimal unboundedPrice) {
    }
}
