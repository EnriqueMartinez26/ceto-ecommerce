package ar.cetobhb.checkout.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** One requested product: an id and a quantity, nothing else. */
record LineRequest(
        @NotNull(message = "INVALID_LINE") @Min(value = 1, message = "INVALID_LINE")
        @Max(value = 9_007_199_254_740_991L, message = "INVALID_LINE") Long productId,
        @NotNull(message = "QUANTITY_BELOW_MIN") @Min(value = 1, message = "QUANTITY_BELOW_MIN")
        @Max(value = 100, message = "QUANTITY_ABOVE_MAX") Integer quantity) {
}
