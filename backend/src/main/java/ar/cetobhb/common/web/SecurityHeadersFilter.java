package ar.cetobhb.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the browser-hardening headers to every response before the chain runs, so successes and errors written by
 * filters, the advice or the container all carry them. The API returns data only: nothing may be sniffed, framed,
 * scripted or leak a referrer. HSTS is the proxy's job. No CORS header is ever added (there is no cross-origin use).
 */
public final class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        chain.doFilter(request, response);
    }
}
