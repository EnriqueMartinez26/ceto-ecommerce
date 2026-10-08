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

    private final JsonMapper json;

    public ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    /** Writes one of the problems Spring Security answers with, using its fixed status, code and detail. */
    public void write(HttpServletRequest request, HttpServletResponse response, SecurityProblem problem)
            throws IOException {
        write(request, response, problem.status(), problem.code(), problem.detail());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(detail);
        // Spring fills 'instance' only for MVC return values; here it must be set, and never be null or unparsable.
        problem.setInstance(instance(request));
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

    private static URI instance(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI());
        } catch (IllegalArgumentException unparsablePath) {
            return URI.create("/");
        }
    }
}
