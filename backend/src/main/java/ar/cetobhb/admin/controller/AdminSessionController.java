package ar.cetobhb.admin.controller;

import java.security.Principal;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/admin/session} (REQ-AUTH-13): the one admin route an anonymous client may call. It says whether the
 * caller is logged in and nothing else (no username, no roles). It is also the documented bootstrap for the CSRF
 * token: the handler reads the token it is given, which makes Security render the {@code XSRF-TOKEN} cookie on this
 * response, and a client calls it again after login and after logout to get the fresh token. It maps GET only.
 */
@RestController
@RequestMapping(path = "/api/admin/session", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminSessionController {

    /** The response body: exactly one member. */
    record SessionStatus(boolean authenticated) {
    }

    @GetMapping
    SessionStatus session(CsrfToken csrfToken, Principal principal) {
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        return new SessionStatus(principal != null);
    }
}
