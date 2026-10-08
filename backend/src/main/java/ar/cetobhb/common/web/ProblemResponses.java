package ar.cetobhb.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an RFC 9457 problem body from code that runs outside Spring MVC (filters, interceptors). It uses the
 * application {@link JsonMapper} so extension members are top-level, exactly as in the advice's responses. Callers
 * pass fixed neutral text only: never a request value or an exception message.
 */
@Component
public class ProblemResponses {

    /** Longest request path a Security problem echoes in {@code instance}; a longer one is replaced by a fixed value. */
    public static final int MAX_INSTANCE_LENGTH = 256;

    /** What {@code instance} says for a 401 or 403 whose request path is longer than the cap. */
    static final String ADMIN_ROOT_INSTANCE = "/api/admin";

    private final JsonMapper json;

    public ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    /**
     * Writes one of the problems Spring Security answers with, using its fixed status, code and detail. A refused
     * spelling ({@link SecurityProblem#REJECTED_REQUEST}) has no {@code instance}: the path is exactly what the firewall
     * refused and is not echoed. A 401 or 403 keeps the request path as {@code instance}, like every public error, but
     * a path longer than {@link #MAX_INSTANCE_LENGTH} is replaced by {@code /api/admin} so the body stays bounded.
     */
    public void write(HttpServletRequest request, HttpServletResponse response, SecurityProblem problem)
            throws IOException {
        URI instance = problem == SecurityProblem.REJECTED_REQUEST ? null : boundedInstance(request);
        write(request, response, problem.status(), problem.code(), problem.detail(), instance);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail) throws IOException {
        write(request, response, status, code, detail, instance(request));
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail, URI instance) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(detail);
        // Spring fills 'instance' only for MVC return values; here it is set (never unparsable), or omitted on purpose.
        if (instance != null) {
            problem.setInstance(instance);
        }
        problem.setProperty("code", code);
        if (request.getAttribute(RequestIdFilter.ATTRIBUTE) instanceof String requestId) {
            problem.setProperty("requestId", requestId);
        }
        byte[] body = json.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static URI boundedInstance(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.length() > MAX_INSTANCE_LENGTH) {
            return URI.create(ADMIN_ROOT_INSTANCE);
        }
        return instance(request);
    }

    private static URI instance(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI());
        } catch (IllegalArgumentException unparsablePath) {
            return URI.create("/");
        }
    }
}
