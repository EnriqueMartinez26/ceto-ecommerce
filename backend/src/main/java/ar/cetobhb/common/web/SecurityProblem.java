package ar.cetobhb.common.web;

import org.springframework.http.HttpStatus;

/**
 * The problem bodies that Spring Security (not Spring MVC) produces for the administration area: no credentials, not
 * allowed, or a request the firewall refused. One source for the status, the stable {@code code} and the fixed neutral
 * {@code detail}; {@code ApiErrorCatalog} derives its public rows from here and {@code ProblemResponses} writes them.
 * A detail never carries a request value, a username or an exception message.
 */
public enum SecurityProblem {

    /** No authenticated principal (an anonymous request, or a session that ended). */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required."),

    /** An authenticated principal that may not do this, including a missing or invalid CSRF token. */
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "The request is not allowed."),

    /** The Security firewall refused the spelling of the path. Same row as an unreadable body (400 MALFORMED_REQUEST). */
    REJECTED_REQUEST(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read.");

    private final HttpStatus status;
    private final String code;
    private final String detail;

    SecurityProblem(HttpStatus status, String code, String detail) {
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
