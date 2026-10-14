package ar.cetobhb.admin.controller;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The body of a product creation (ACAT-02, ACAT-03). A {@code BASE} product carries no link; a {@code COMBO} needs
 * {@code baseProductId} and {@code units}, and the pairing is checked by the controller. The prices, the name, the unit
 * count (2 to 100) and the base are judged by the catalog rules, which answer with a reason; the bounds here only keep
 * every input small. Unknown properties are a 400 (strict mapper): {@code active}, {@code id} or {@code version} cannot be
 * sent.
 */
record CreateProductRequest(
        @NotNull ProductType type,
        @NotNull @Size(max = AdminBounds.NAME_MAX) String name,
        @NotNull @DecimalMin(AdminBounds.MONEY_MIN) @DecimalMax(AdminBounds.MONEY_MAX)
        @Digits(integer = AdminBounds.MONEY_INTEGER_DIGITS, fraction = AdminBounds.MONEY_FRACTION_DIGITS)
        BigDecimal price,
        @DecimalMin(AdminBounds.MONEY_MIN) @DecimalMax(AdminBounds.MONEY_MAX)
        @Digits(integer = AdminBounds.MONEY_INTEGER_DIGITS, fraction = AdminBounds.MONEY_FRACTION_DIGITS)
        BigDecimal compareAtPrice,
        @Min(1) @Max(AdminBounds.MAX_ID) Long baseProductId,
        @Min(1) @Max(1000) Integer units) {

    /** The name is a catalog value, not printed (CLAUDE.md section 5: no product or buyer data in logs). */
    @Override
    public String toString() {
        return "CreateProductRequest[type=" + type + "]";
    }
}
