package ar.cetobhb.common.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Keeps Boot's {@code /error} endpoint an internal one: only a container ERROR dispatch may reach it. A client that
 * requests {@code /error} directly gets the standard 404 (the advice renders the exception) instead of a bare 500
 * from an endpoint that is not meant to be public. Real error dispatches still render Boot's generic body
 * (D-20261007-23). It is registered for {@code /error} only.
 */
public final class ErrorEndpointGuardInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws NoResourceFoundException {
        if (request.getDispatcherType() != DispatcherType.ERROR) {
            throw new NoResourceFoundException(HttpMethod.valueOf(request.getMethod()), "error", "error");
        }
        return true;
    }
}
