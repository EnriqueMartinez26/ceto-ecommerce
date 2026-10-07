package ar.cetobhb.common.controller;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.common.domain.DomainException;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockAlreadyConfiguredException;
import ar.cetobhb.inventory.domain.StockNotConfiguredException;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.ReservationNotExpiredException;
import java.util.Map;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Exception-to-response table (REQ-ERR-03). Every {@code detail} is a fixed neutral English string: it never carries a
 * request value, an exception message or an identifier. Domain exceptions are matched by exact class and a reflective
 * test fails the build if a concrete subclass has no entry, so none falls through to 500.
 */
final class ApiErrorCatalog {

    static final ApiError VALIDATION = new ApiError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The request is invalid.", Level.DEBUG);
    static final ApiError MALFORMED = new ApiError(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
            "The request could not be read.", Level.DEBUG);
    static final ApiError UNSUPPORTED_MEDIA_TYPE = new ApiError(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "UNSUPPORTED_MEDIA_TYPE", "The content type is not supported.", null);
    static final ApiError NOT_ACCEPTABLE = new ApiError(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE",
            "The requested representation is not available.", null);
    static final ApiError METHOD_NOT_ALLOWED = new ApiError(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
            "The method is not allowed for this resource.", null);
    static final ApiError NOT_FOUND = new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The resource was not found.", null);
    static final ApiError PAYLOAD_TOO_LARGE = new ApiError(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
            "The request is too large.", null);
    static final ApiError CONFLICT = new ApiError(HttpStatus.CONFLICT, "CONFLICT",
            "The request conflicts with the current state.", Level.WARN);
    static final ApiError SERVICE_BUSY = new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
            "The service is busy. Try again shortly.", Level.WARN);
    static final ApiError INTERNAL = new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "An unexpected error occurred.", Level.ERROR);

    private static final Map<Class<? extends DomainException>, ApiError> DOMAIN_ERRORS = Map.ofEntries(
            Map.entry(ValidationException.class, VALIDATION),
            Map.entry(ProductNotAvailableException.class, new ApiError(HttpStatus.CONFLICT, "PRODUCT_UNAVAILABLE",
                    "One or more products are not available.", Level.DEBUG)),
            // The buyer sees "unavailable"; the operator gets the ERROR line to fix the missing stock row.
            Map.entry(StockNotConfiguredException.class, new ApiError(HttpStatus.CONFLICT, "PRODUCT_UNAVAILABLE",
                    "One or more products are not available.", Level.ERROR)),
            Map.entry(InsufficientStockException.class, new ApiError(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                    "There is not enough stock for this order.", Level.DEBUG)),
            Map.entry(IdempotencyConflictException.class, new ApiError(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for a different order.", Level.INFO)),
            // No public endpoint reaches these three yet; explicit rows keep a future endpoint from returning 500.
            Map.entry(IllegalOrderTransitionException.class, CONFLICT),
            Map.entry(StockAlreadyConfiguredException.class, CONFLICT),
            Map.entry(OrderNotFoundException.class, NOT_FOUND),
            Map.entry(ReservationNotExpiredException.class, INTERNAL));

    private ApiErrorCatalog() {
    }

    /**
     * Row for a framework 4xx that has no dedicated mapping: keeps the status and derives the code from it, so a 410 or
     * 422 is never mislabelled as an unreadable body.
     */
    static ApiError clientError(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> {
                HttpStatus known = HttpStatus.resolve(status.value());
                yield new ApiError(known == null ? HttpStatus.BAD_REQUEST : known, "BAD_REQUEST",
                        "The request cannot be processed.", Level.DEBUG);
            }
        };
    }

    static Map<Class<? extends DomainException>, ApiError> domainErrors() {
        return DOMAIN_ERRORS;
    }
}
