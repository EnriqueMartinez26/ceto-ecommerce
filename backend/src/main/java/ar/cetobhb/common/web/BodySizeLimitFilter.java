package ar.cetobhb.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the request body (REQ-ABU body limit). A declared {@code Content-Length} over the limit is answered 413 before a
 * single byte is read; any other body (chunked, or a length that was understated) is handed to the application through
 * a {@link LimitedBodyRequest}, so reading past the limit throws {@link BodyTooLargeException}, which the error advice
 * also turns into 413. It runs inside the request-id and security-header filters (their values are on the 413) and
 * before the rate-limit interceptor, so an oversized request is rejected without spending a token. The body is never
 * logged.
 */
public final class BodySizeLimitFilter extends OncePerRequestFilter {

    static final String CODE = "PAYLOAD_TOO_LARGE";
    static final String DETAIL = "The request is too large.";

    private final ProblemResponses problems;
    private final int maxBytes;

    public BodySizeLimitFilter(ProblemResponses problems, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.problems = problems;
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            // The unread body makes the connection unusable for a next request: tell the client and the container.
            response.setHeader("Connection", "close");
            problems.write(request, response, HttpStatus.CONTENT_TOO_LARGE, CODE, DETAIL);
            return;
        }
        chain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
    }
}
