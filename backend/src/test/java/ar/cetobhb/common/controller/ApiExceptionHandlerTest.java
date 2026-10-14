package ar.cetobhb.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.catalog.domain.ProductNotFoundException;
import ar.cetobhb.catalog.domain.StaleProductVersionException;
import ar.cetobhb.common.domain.DomainException;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.common.web.BodyTooLargeException;
import ar.cetobhb.catalog.domain.StaleProductVersionException;
import ar.cetobhb.inventory.domain.InsufficientStockException;
import ar.cetobhb.inventory.domain.StockAlreadyConfiguredException;
import ar.cetobhb.inventory.domain.StockNotAdjustableException;
import ar.cetobhb.inventory.domain.StockNotConfiguredException;
import ar.cetobhb.orders.domain.CheckoutInProgressException;
import ar.cetobhb.orders.domain.IdempotencyConflictException;
import ar.cetobhb.orders.domain.IllegalOrderTransitionException;
import ar.cetobhb.orders.domain.OrderNotFoundException;
import ar.cetobhb.orders.domain.OrderStatus;
import ar.cetobhb.orders.domain.ReservationNotExpiredException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.core.MethodParameter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Whole error table through a standalone MockMvc: the advice is exercised without a Spring context or a database. */
class ApiExceptionHandlerTest {

    private static final String SECRET = "boom: +5493510000000";
    private static final AtomicReference<Throwable> THROWN = new AtomicReference<>();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        THROWN.set(null);
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @RestController
    static class ThrowingController {

        record Payload(@Size(max = 3, message = "BLANK_ADDRESS") String name) {
        }

        @GetMapping("/boom")
        void boom() throws Throwable {
            throw THROWN.get();
        }

        @PostMapping(path = "/valid", consumes = MediaType.APPLICATION_JSON_VALUE)
        void valid(@Valid @RequestBody Payload payload) {
        }

        @GetMapping(path = "/items/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
        void item(@PathVariable @Min(1) @Max(100) long id) {
        }
    }

    static Stream<Arguments> table() {
        return Stream.of(
                Arguments.of(new ValidationException(ReasonCode.EMPTY_CART), 400, "VALIDATION_ERROR"),
                Arguments.of(new ProductNotAvailableException(Set.of(3L)), 409, "PRODUCT_UNAVAILABLE"),
                Arguments.of(new StockNotConfiguredException(7L), 409, "PRODUCT_UNAVAILABLE"),
                Arguments.of(new InsufficientStockException(Set.of(3L)), 409, "INSUFFICIENT_STOCK"),
                Arguments.of(new IdempotencyConflictException(42L), 409, "IDEMPOTENCY_CONFLICT"),
                Arguments.of(new IllegalOrderTransitionException(OrderStatus.PAGADO, OrderStatus.PAGO_PENDIENTE), 409,
                        "ILLEGAL_TRANSITION"),
                Arguments.of(new StockAlreadyConfiguredException(7L), 409, "CONFLICT"),
                Arguments.of(new StockNotAdjustableException(7L), 409, "CONFLICT"),
                Arguments.of(new StaleProductVersionException(7L), 409, "CONFLICT"),
                Arguments.of(new OrderNotFoundException(9L), 404, "NOT_FOUND"),
                Arguments.of(new ProductNotFoundException(9L), 404, "NOT_FOUND"),
                Arguments.of(new ReservationNotExpiredException(), 500, "INTERNAL_ERROR"),
                Arguments.of(new DomainException("UNMAPPED"), 500, "INTERNAL_ERROR"),
                Arguments.of(new DataIntegrityViolationException(SECRET), 409, "CONFLICT"),
                // Extension of REQ-ERR-03: a lost optimistic-lock race is a conflict with the current state (409),
                // not a retryable overload, so it is deliberately not in the 503 SERVICE_BUSY family below.
                Arguments.of(new OptimisticLockingFailureException(SECRET), 409, "CONFLICT"),
                Arguments.of(new NoResourceFoundException(HttpMethod.GET, "/nope", "nope"), 404, "NOT_FOUND"),
                Arguments.of(new RuntimeException(SECRET), 500, "INTERNAL_ERROR"),
                // Server-side framework faults extend 4xx-family exception types but are 500s (REQ-ERR-01).
                Arguments.of(new ConversionNotSupportedException(SECRET, Long.class, null), 500, "INTERNAL_ERROR"),
                Arguments.of(missingPathVariable(), 500, "INTERNAL_ERROR"));
    }

    private static MissingPathVariableException missingPathVariable() {
        try {
            Method method = ThrowingController.class.getDeclaredMethod("item", long.class);
            return new MissingPathVariableException("id", new MethodParameter(method, 0));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @MethodSource("table")
    void mapsEveryExceptionToItsFixedProblem(Throwable thrown, int status, String code) throws Exception {
        THROWN.set(thrown);

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/boom"));
        assertThat(body(result)).doesNotContain("+54", "boom:", "UNMAPPED", "STOCK_", "42", "ORDER_NOT_FOUND",
                "PAGADO", "PAGO_PENDIENTE", "->");
    }

    static Stream<Arguments> clientErrorResponses() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, "BAD_REQUEST"),
                Arguments.of(HttpStatus.NOT_FOUND, "NOT_FOUND"),
                Arguments.of(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED"),
                Arguments.of(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE"),
                Arguments.of(HttpStatus.GONE, "BAD_REQUEST"),
                Arguments.of(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE"),
                Arguments.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE"),
                Arguments.of(HttpStatus.UNPROCESSABLE_CONTENT, "BAD_REQUEST"));
    }

    @ParameterizedTest
    @MethodSource("clientErrorResponses")
    void otherFrameworkClientErrorsKeepTheirStatusWithACodeDerivedFromIt(HttpStatus status, String code)
            throws Exception {
        THROWN.set(new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, SECRET), null));

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(status().is(status.value())).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status.value()));
        assertThat(body(result)).doesNotContain("boom:", "+54");
    }

    /**
     * Exceptions Spring classifies as lock, pool or timeout failures. A raw JdbcTemplate lock timeout is the exception
     * (verification U-10): it surfaces as {@code UncategorizedSQLException} and the advice maps it by its SQLState
     * 55P03 (see the dedicated tests below).
     */
    static Stream<Throwable> serviceBusyFamily() {
        return Stream.of(new CannotAcquireLockException(SECRET), new PessimisticLockingFailureException(SECRET),
                new QueryTimeoutException(SECRET), new CannotCreateTransactionException(SECRET),
                new DataAccessResourceFailureException(SECRET), new TransientDataAccessResourceException(SECRET),
                new jakarta.persistence.LockTimeoutException(SECRET),
                new jakarta.persistence.PessimisticLockException(SECRET),
                new jakarta.persistence.QueryTimeoutException(SECRET));
    }

    @ParameterizedTest
    @MethodSource("serviceBusyFamily")
    void lockPoolAndTimeoutFailuresAreServiceBusyWithRetryAfter(Throwable thrown) throws Exception {
        THROWN.set(thrown);

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
        assertThat(body(result)).doesNotContain("+54");
    }

    @Test
    void aRawJdbcLockTimeoutIsServiceBusyToo() throws Exception {
        // verification U-10: JdbcTemplate surfaces SQLState 55P03 (lock_not_available) as UncategorizedSQLException.
        THROWN.set(new org.springframework.jdbc.UncategorizedSQLException("task", "select " + SECRET,
                new java.sql.SQLException(SECRET, "55P03")));

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
        assertThat(body(result)).doesNotContain("+54", "55P03", "select");
    }

    @Test
    void aRawJdbcFailureWithTheLockTimeoutStateDeepInTheCauseChainIsServiceBusy() throws Exception {
        THROWN.set(new org.springframework.jdbc.UncategorizedSQLException("task", "sql",
                new java.sql.SQLException("wrapper", "XX000", new java.sql.SQLException(SECRET, "55P03"))));

        mvc.perform(get("/boom")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
    }

    @Test
    void aRawJdbcFailureWithAnyOtherStateStaysAnInternalError() throws Exception {
        THROWN.set(new org.springframework.jdbc.UncategorizedSQLException("task", "sql",
                new java.sql.SQLException(SECRET, "40P01")));

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(body(result)).doesNotContain("+54");
    }

    @Test
    void aCheckoutInProgressIsServiceBusyAndAsksForARetryInOneSecond() throws Exception {
        THROWN.set(new CheckoutInProgressException());

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                .andExpect(jsonPath("$.detail").value("The service is busy. Try again shortly."));
    }

    @Test
    void validationExceptionCarriesItsReasonAndNothingElse() throws Exception {
        THROWN.set(new ValidationException(ReasonCode.QUANTITY_ABOVE_MAX));

        mvc.perform(get("/boom")).andExpect(jsonPath("$.reason").value("QUANTITY_ABOVE_MAX"))
                .andExpect(jsonPath("$.productIds").doesNotExist());
    }

    @Test
    void productUnavailableListsTheIdsSorted() throws Exception {
        THROWN.set(new ProductNotAvailableException(Set.of(9L, 2L)));

        mvc.perform(get("/boom")).andExpect(jsonPath("$.productIds[0]").value(2))
                .andExpect(jsonPath("$.productIds[1]").value(9)).andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void unexpectedExceptionNeverLeaksItsMessage() throws Exception {
        THROWN.set(new RuntimeException(SECRET));

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
        assertThat(body(result)).doesNotContain("boom:", "+54", "RuntimeException", "at ar.cetobhb");
    }

    @Test
    void beanValidationReasonComesFromTheConstraintMessage() throws Exception {
        mvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"SECRETVALUE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.reason").value("BLANK_ADDRESS"))
                .andExpect(content().string(not(containsString("SECRET"))));
    }

    @Test
    void methodValidationFailureOnAPathVariableIsAValidationError() throws Exception {
        mvc.perform(get("/items/0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void nonNumericPathVariableIsAValidationErrorWithoutTheConversionMessage() throws Exception {
        // The path itself is public data and is echoed as `instance`; the conversion message is not.
        ResultActions result = mvc.perform(get("/items/abc"));

        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request is invalid."));
        assertThat(body(result)).doesNotContain("Failed to convert", "NumberFormat");
    }

    @Test
    void malformedJsonIsMalformedRequestWithoutEchoingTheInput() throws Exception {
        ResultActions result = mvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": SECRETVALUE"));

        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("The request could not be read."));
        assertThat(body(result)).doesNotContain("SECRET");
    }

    @Test
    void aFrameworkClientErrorIsNeverCached() throws Exception {
        mvc.perform(post("/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\": x"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void aDomainConflictIsNeverCached() throws Exception {
        THROWN.set(new IdempotencyConflictException(42L));

        mvc.perform(get("/boom"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void anUnexpectedFailureIsNeverCached() throws Exception {
        THROWN.set(new RuntimeException(SECRET));

        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void nonJsonContentTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post("/valid").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void wrongMethodIsMethodNotAllowedWithAllowHeader() throws Exception {
        mvc.perform(post("/boom")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unacceptableRepresentationIsNotAcceptable() throws Exception {
        mvc.perform(get("/items/5").accept(MediaType.APPLICATION_XML)).andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void problemBodyHasNoTypeMember() throws Exception {
        THROWN.set(new RuntimeException(SECRET));

        mvc.perform(get("/boom")).andExpect(jsonPath("$.type").doesNotExist());
    }

    @Test
    void instanceFallsBackToRootWhenTheRequestPathIsNotAValidUri() throws Exception {
        mvc.perform(get("/boom").with(request -> {
            request.setRequestURI("/not a uri");
            return request;
        })).andExpect(status().isNotFound()).andExpect(jsonPath("$.instance").value("/"));
    }

    @Test
    void requestIdAttributeIsEchoedWhenPresent() throws Exception {
        THROWN.set(new RuntimeException(SECRET));

        mvc.perform(get("/boom").requestAttr("requestId", "rid-1")).andExpect(jsonPath("$.requestId").value("rid-1"));
    }

    @Test
    void anUnreadableBodyCausedByTheBodyLimitIs413NotMalformed() throws Exception {
        THROWN.set(new HttpMessageNotReadableException("I/O error while reading input message",
                new RuntimeException("wrapper", new BodyTooLargeException(16_384)),
                new MockHttpInputMessage(new byte[0])));

        ResultActions result = mvc.perform(get("/boom"));

        result.andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value("The request is too large."))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Connection", "close"));
        assertThat(body(result)).doesNotContain("16384");
    }

    @Test
    void theBodyLimitFailureIs413EvenWhenItReachesTheAdviceUnwrapped() throws Exception {
        THROWN.set(new BodyTooLargeException(16_384));

        mvc.perform(get("/boom")).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(header().string("Connection", "close"));
    }

    @Test
    void otherErrorsDoNotAskForTheConnectionToBeClosed() throws Exception {
        THROWN.set(new RuntimeException(SECRET));

        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Connection"));
    }

    @Test
    void anUnreadableBodyWithoutTheLimitInItsCauseChainStaysMalformed() throws Exception {
        THROWN.set(new HttpMessageNotReadableException("unreadable", new RuntimeException("a", new java.io.IOException("b")),
                new MockHttpInputMessage(new byte[0])));

        mvc.perform(get("/boom")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void theBodyLimitIsFoundSeveralCausesDeep() throws Exception {
        Throwable chain = new BodyTooLargeException(16_384);
        for (int i = 0; i < 5; i++) {
            chain = new RuntimeException("wrapper " + i, chain);
        }
        THROWN.set(new HttpMessageNotReadableException("unreadable", chain, new MockHttpInputMessage(new byte[0])));

        mvc.perform(get("/boom")).andExpect(status().isContentTooLarge());
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
