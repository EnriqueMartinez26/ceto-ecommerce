package ar.cetobhb.common.controller;

import ar.cetobhb.catalog.domain.ProductNotAvailableException;
import ar.cetobhb.common.domain.DomainException;
import ar.cetobhb.common.domain.RateLimitedException;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import ar.cetobhb.common.web.SafeErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The only owner of error responses (REQ-ERR-01): every failure becomes an RFC 9457 {@code application/problem+json}
 * body built from {@link ApiErrorCatalog}. Responses carry fixed neutral text only. Logging never passes a
 * {@code Throwable} or its message to the logger for framework, persistence or Jackson exceptions (those messages echo
 * rejected values and, for Postgres CHECK violations, a whole row of personal data, CLAUDE.md section 5): it logs
 * {@link SafeErrors} summaries or class names only.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** Request attribute holding the per-request id, set by the request-id filter. */
    static final String REQUEST_ID_ATTRIBUTE = "requestId";

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> handleDomain(DomainException ex, HttpServletRequest request) {
        ApiError error = ApiErrorCatalog.domainErrors().get(ex.getClass());
        if (error == null) {
            error = ApiErrorCatalog.INTERNAL;
            LOG.error("Unmapped domain exception {}", ex.getClass().getName());
        } else if (error.logLevel() != null) {
            // Domain messages carry codes and ids only, never personal data (DomainException contract).
            LOG.atLevel(error.logLevel()).log("{} {}", error.code(), ex.getMessage());
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        if (ex instanceof ValidationException validation) {
            extras.put("reason", validation.reason().name());
        } else if (ex instanceof ProductNotAvailableException unavailable
                && error.code().equals("PRODUCT_UNAVAILABLE")) {
            extras.put("productIds", List.copyOf(unavailable.productIds()));
        }
        HttpHeaders headers = new HttpHeaders();
        if (ex instanceof RateLimitedException limited) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
        }
        return respond(error, request, headers, extras);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ResponseEntity<Object> handleConflict(Exception ex, HttpServletRequest request) {
        return respondLogging(ApiErrorCatalog.CONFLICT, ex, request, new HttpHeaders());
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class,
            CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class, jakarta.persistence.LockTimeoutException.class,
            jakarta.persistence.PessimisticLockException.class, jakarta.persistence.QueryTimeoutException.class})
    ResponseEntity<Object> handleServiceBusy(Exception ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return respondLogging(ApiErrorCatalog.SERVICE_BUSY, ex, request, headers);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        return respondLogging(ApiErrorCatalog.INTERNAL, ex, request, new HttpHeaders());
    }

    /** Every Spring MVC exception handled by the base class ends here; its default body and detail are discarded. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest webRequest) {
        HttpServletRequest request = webRequest instanceof ServletWebRequest servlet ? servlet.getRequest() : null;
        ApiError error = classify(ex, statusCode);
        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            responseHeaders.putAll(headers);
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        if (error == ApiErrorCatalog.SERVICE_BUSY) {
            responseHeaders.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        if (error == ApiErrorCatalog.VALIDATION) {
            String reason = reasonOf(ex);
            if (reason != null) {
                extras.put("reason", reason);
            }
        }
        return respondLogging(error, ex, request, responseHeaders, extras);
    }

    private static ApiError classify(Exception ex, HttpStatusCode status) {
        if (ex instanceof AsyncRequestTimeoutException) {
            return ApiErrorCatalog.SERVICE_BUSY;
        }
        // Server faults first: ConversionNotSupportedException extends TypeMismatchException and
        // MissingPathVariableException extends ServletRequestBindingException, yet both are 500s.
        if (status.is5xxServerError()) {
            return ApiErrorCatalog.INTERNAL;
        }
        if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException
                || ex instanceof TypeMismatchException) {
            return ApiErrorCatalog.VALIDATION;
        }
        if (ex instanceof HttpMessageNotReadableException || ex instanceof ServletRequestBindingException) {
            return ApiErrorCatalog.MALFORMED;
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return ApiErrorCatalog.UNSUPPORTED_MEDIA_TYPE;
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return ApiErrorCatalog.NOT_ACCEPTABLE;
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return ApiErrorCatalog.METHOD_NOT_ALLOWED;
        }
        if (ex instanceof NoHandlerFoundException || ex instanceof NoResourceFoundException) {
            return ApiErrorCatalog.NOT_FOUND;
        }
        return ApiErrorCatalog.clientError(status);
    }

    /**
     * The first structural reason (a constraint message that is a {@link ReasonCode}) by sorted field path, or
     * {@code null}. Rejected values are never read.
     */
    private static String reasonOf(Exception ex) {
        List<Map.Entry<String, String>> violations = new ArrayList<>();
        if (ex instanceof MethodArgumentNotValidException invalid) {
            invalid.getBindingResult().getFieldErrors()
                    .forEach(e -> violations.add(Map.entry(e.getField(), String.valueOf(e.getDefaultMessage()))));
        } else if (ex instanceof HandlerMethodValidationException invalid) {
            invalid.getParameterValidationResults().forEach(result -> {
                if (result instanceof ParameterErrors errors) {
                    for (FieldError e : errors.getFieldErrors()) {
                        violations.add(Map.entry(e.getField(), String.valueOf(e.getDefaultMessage())));
                    }
                } else {
                    for (MessageSourceResolvable e : result.getResolvableErrors()) {
                        violations.add(Map.entry("", String.valueOf(e.getDefaultMessage())));
                    }
                }
            });
        }
        return violations.stream()
                .sorted(Map.Entry.<String, String>comparingByKey().thenComparing(Comparator.comparing(Map.Entry::getValue)))
                .map(Map.Entry::getValue)
                .filter(ApiExceptionHandler::isReasonCode)
                .findFirst().orElse(null);
    }

    private static boolean isReasonCode(String message) {
        for (ReasonCode code : ReasonCode.values()) {
            if (code.name().equals(message)) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Object> respondLogging(ApiError error, Throwable ex, HttpServletRequest request,
            HttpHeaders headers) {
        return respondLogging(error, ex, request, headers, Map.of());
    }

    private ResponseEntity<Object> respondLogging(ApiError error, Throwable ex, HttpServletRequest request,
            HttpHeaders headers, Map<String, Object> extras) {
        if (error.logLevel() != null) {
            LOG.atLevel(error.logLevel()).log("{} {}", error.code(), describe(error, ex));
        }
        return respond(error, request, headers, extras);
    }

    /**
     * Server-side failures and conflicts get the PII-free {@link SafeErrors} summary (class chain, SQLState, constraint
     * name, root frames); client mistakes only the top class name. Messages are never logged.
     */
    private static String describe(ApiError error, Throwable ex) {
        if (error.status().is5xxServerError() || error == ApiErrorCatalog.CONFLICT) {
            return SafeErrors.describe(ex);
        }
        return ex.getClass().getName();
    }

    private static ResponseEntity<Object> respond(ApiError error, HttpServletRequest request, HttpHeaders headers,
            Map<String, Object> extras) {
        ProblemDetail problem = ProblemDetail.forStatus(error.status());
        problem.setTitle(error.status().getReasonPhrase());
        problem.setDetail(error.detail());
        problem.setInstance(instance(request));
        problem.setProperty("code", error.code());
        extras.forEach(problem::setProperty);
        if (request != null && request.getAttribute(REQUEST_ID_ATTRIBUTE) instanceof String requestId) {
            problem.setProperty("requestId", requestId);
        }
        // Error responses are per-request and may carry ids: no cache may store or reuse them.
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.set(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ResponseEntity<>(problem, responseHeaders, error.status());
    }

    /** Always non-null: Spring would call {@code URI.create} itself on a null instance and could throw. */
    private static URI instance(HttpServletRequest request) {
        try {
            return URI.create(request == null ? "/" : request.getRequestURI());
        } catch (IllegalArgumentException unparsablePath) {
            return URI.create("/");
        }
    }
}
