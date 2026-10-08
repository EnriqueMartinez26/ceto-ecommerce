package ar.cetobhb.checkout.controller;

import ar.cetobhb.orders.service.OrderPlacementService;
import ar.cetobhb.orders.service.PlacedOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public checkout (REQ-CHK-01). It translates HTTP to the placement service and back; every business rule, including
 * the idempotency key format, is judged by the core. The header is optional here so that a missing key gets the same
 * VALIDATION_ERROR/MISSING_IDEMPOTENCY_KEY answer as a malformed one.
 *
 * <p>Because a constraint sits on the header, Spring reports body violations as {@code HandlerMethodValidationException}
 * (verification U-13); the advice handles it like {@code MethodArgumentNotValidException}.
 */
@RestController
@RequestMapping(path = "/api/checkout/orders", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
class CheckoutController {

    private final OrderPlacementService placement;

    CheckoutController(OrderPlacementService placement) {
        this.placement = placement;
    }

    /** 201 for a new order, 200 for an idempotent replay. Never cached, and no {@code Location}: there is no GET. */
    @PostMapping
    ResponseEntity<PlacedOrderResponse> place(
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Size(max = 64, message = "MISSING_IDEMPOTENCY_KEY") String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlacedOrder placed = placement.placeOrder(CheckoutMapper.toCommand(idempotencyKey, request));
        return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(PlacedOrderResponse.from(placed));
    }
}
