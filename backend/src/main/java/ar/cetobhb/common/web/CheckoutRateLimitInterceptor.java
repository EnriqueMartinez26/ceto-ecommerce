package ar.cetobhb.common.web;

import ar.cetobhb.common.domain.ClientIpKey;
import ar.cetobhb.common.domain.RateLimitedException;
import ar.cetobhb.common.domain.TokenBucketLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spends one attempt of the caller's bucket before the checkout handler runs (REQ-ABU-02). Being an interceptor it
 * runs before the body is read, so a rejected attempt costs no JSON parsing and no database access, and every attempt
 * that reaches the handler counts, valid or not. The key comes ONLY from {@code getRemoteAddr()}: behind the trusted
 * proxy the container has already replaced it with the forwarded client (REQ-ABU-03), and this class never reads
 * {@code X-Forwarded-For} itself. The address is never logged.
 *
 * <p>Requests Spring cannot map to the handler (wrong content type or method) are answered before any interceptor
 * runs; they read no body either, so they cost almost nothing and are not counted.
 */
public final class CheckoutRateLimitInterceptor implements HandlerInterceptor {

    private final TokenBucketLimiter limiter;

    public CheckoutRateLimitInterceptor(TokenBucketLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        TokenBucketLimiter.Decision decision = limiter.tryAcquire(ClientIpKey.of(request.getRemoteAddr()));
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
        return true;
    }
}
