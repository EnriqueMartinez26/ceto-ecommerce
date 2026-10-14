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
 * The body of a product edit (ACAT-04): a FULL replacement of name, price and offer price, guarded by the version the
 * client read. An omitted or null {@code compareAtPrice} CLEARS the offer price (D-20261008-21): the field is not "keep
 * the old one". The combo shape (type, base, units) and the active flag cannot be sent: they are unknown properties, a
 * 400 from the strict mapper.
 */
record UpdateProductRequest(
        @NotNull @Size(max = AdminBounds.NAME_MAX) String name,
        @NotNull @DecimalMin(AdminBounds.MONEY_MIN) @DecimalMax(AdminBounds.MONEY_MAX)
        @Digits(integer = AdminBounds.MONEY_INTEGER_DIGITS, fraction = AdminBounds.MONEY_FRACTION_DIGITS)
        BigDecimal price,
        @DecimalMin(AdminBounds.MONEY_MIN) @DecimalMax(AdminBounds.MONEY_MAX)
        @Digits(integer = AdminBounds.MONEY_INTEGER_DIGITS, fraction = AdminBounds.MONEY_FRACTION_DIGITS)
        BigDecimal compareAtPrice,
        @NotNull @Min(0) @Max(AdminBounds.MAX_ID) Long version) {

    /** The name is a catalog value, not printed (CLAUDE.md section 5: no product or buyer data in logs). */
    @Override
    public String toString() {
        return "UpdateProductRequest[version=" + version + "]";
    }
}
