package ar.cetobhb.common.controller;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.StaleProductVersionException;
import ar.cetobhb.common.domain.DomainException;
import ar.cetobhb.common.domain.RateLimitedException;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.common.web.SecurityProblem;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockAlreadyConfiguredException;
import ar.cetobhb.inventory.domain.StockNotAdjustableException;
import ar.cetobhb.inventory.domain.StockNotConfiguredException;
import ar.cetobhb.orders.domain.CheckoutInProgressException;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.ReservationNotExpiredException;
import ar.cetobhb.orders.domain.TooManyPendingOrdersException;
import java.util.EnumMap;
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
            // A client can trigger this 429 at will, so it is logged at DEBUG only and never with the client address.
            Map.entry(RateLimitedException.class, new ApiError(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Try again later.", Level.DEBUG)),
            Map.entry(TooManyPendingOrdersException.class, new ApiError(HttpStatus.TOO_MANY_REQUESTS,
                    "TOO_MANY_PENDING_ORDERS", "Too many pending orders for this phone.", Level.DEBUG)),
            // A second checkout of the same phone while one is running (D-20261008-01): a plain busy answer. A client
            // can trigger it at will, so DEBUG only. Same status, code and detail as SERVICE_BUSY; ApiExceptionHandler
            // adds Retry-After: 1.
            Map.entry(CheckoutInProgressException.class, new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_BUSY",
                    "The service is busy. Try again shortly.", Level.DEBUG)),
            // No public endpoint reaches these three yet; explicit rows keep a future endpoint from returning 500.
            Map.entry(IllegalOrderTransitionException.class, CONFLICT),
            Map.entry(StockAlreadyConfiguredException.class, CONFLICT),
            // Admin stock adjustment of a combo or of a product without a stock row (AINV-04): WARN via CONFLICT.
            Map.entry(StockNotAdjustableException.class, CONFLICT),
            // Admin product edit based on a version that is no longer current (ACAT-04): a conflict with the current state.
            Map.entry(StaleProductVersionException.class, CONFLICT),
            Map.entry(OrderNotFoundException.class, NOT_FOUND),
            Map.entry(ProductNotFoundException.class, NOT_FOUND),
            Map.entry(ReservationNotExpiredException.class, INTERNAL));

    private ApiErrorCatalog() {
    }

    /**
     * The rows Spring Security answers with (written by {@code ProblemResponses}, not by the advice), derived from the
     * {@link SecurityProblem} enum so the text has one source. Nothing is logged for them: an anonymous client can
     * trigger a 401 at will and the line would carry no information.
     *
     * <p>Only the tests read it: it is the test-side agreement check between the enum and the documented rows
     * ({@code SecurityProblemTest}, {@code ApiDocConsistencyTest}).
     */
    static Map<SecurityProblem, ApiError> securityRows() {
        Map<SecurityProblem, ApiError> rows = new EnumMap<>(SecurityProblem.class);
        for (SecurityProblem problem : SecurityProblem.values()) {
            rows.put(problem, new ApiError(problem.status(), problem.code(), problem.detail(), null));
        }
        return Map.copyOf(rows);
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
