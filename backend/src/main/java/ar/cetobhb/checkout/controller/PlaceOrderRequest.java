package ar.cetobhb.checkout.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Checkout request body. The constraints are absolute structural bounds only; their {@code message} is the
 * {@code ReasonCode} the client would get from the business rules. The business limits live in the normalizer.
 * There is no price, total or status member: unknown properties are rejected (CLAUDE.md rule 7).
 */
record PlaceOrderRequest(@Valid BuyerRequest buyer,
        @Size(max = 50, message = "TOO_MANY_LINES") List<@Valid LineRequest> lines) {
}
