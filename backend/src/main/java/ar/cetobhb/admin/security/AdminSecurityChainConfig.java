package ar.cetobhb.admin.security;

import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * The administration chains (slice X1 skeleton, hardened in review fix batch 5, problem bodies in slice X3): every
 * request under {@code /api/admin/} needs an authenticated principal. An anonymous one gets a 401
 * {@code UNAUTHENTICATED} problem and a denied one (including a missing or invalid CSRF token) a 403
 * {@code FORBIDDEN} problem, both written by {@link ProblemResponses} so they match the advice's shape and carry the
 * request id and hardening headers that filters ahead of Security already set. They replace Boot's default chain, which
 * would otherwise answer for {@code /*} with form login and a generated password. Login and the session policy arrive
 * in the following slices; until then nothing can authenticate, so every admin path is closed.
 *
 * <p>Two chains, in this order: the admin chain, which matches {@code /api/admin/**}, and a fallback with no matcher
 * that denies everything. The servlet filter mapping ({@link SecurityFilterScopeConfig}) lets only {@code /api/admin/*}
 * reach Security, so the fallback never sees a public route; it exists so that an admin path the admin chain's matcher
 * fails to cover is refused instead of reaching a handler with no authentication.
 *
 * <p>Headers: Security's defaults stay on (design AD-A11): {@code Cache-Control: no-store} family,
 * {@code X-Frame-Options}, {@code X-Content-Type-Options} (the filter that runs first already set it, so it is not
 * repeated). HSTS is switched off because the proxy owns it. There is no {@code cors()}: no
 * {@code Access-Control-Allow-Origin} is ever sent.
 */
@Configuration(proxyBeanMethods = false)
class AdminSecurityChainConfig {

    static final String LOGIN_PATH = "/api/admin/login";
    static final String LOGOUT_PATH = "/api/admin/logout";

    /** The admin chain is consulted first. */
    static final int ADMIN_CHAIN_ORDER = 0;

    @Bean
    @Order(ADMIN_CHAIN_ORDER)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, ProblemResponses problems,
            @Qualifier("adminAuthenticationManager") AuthenticationManager authenticationManager) throws Exception {
        // The default repository keeps the token in the HTTP session, which would open a server session (and set a
        // JSESSIONID) for every anonymous POST. The cookie repository keeps the token on the client and the server
        // stateless until someone logs in. The cookie is readable by the SPA (that is how it echoes it back), Secure
        // and SameSite=Strict.
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));
        return http
                .securityMatcher("/api/admin/**")
                // The one anonymous allowance: GET /api/admin/session, matched exactly (method and path as written).
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(new SessionBootstrapRequest()).permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                // The login request is shaped (no query string, urlencoded, one value per field) before CSRF reads it.
                .addFilterBefore(new LoginRequestGuardFilter(LOGIN_PATH, problems), CsrfFilter.class)
                // The login is a form post (UsernamePasswordAuthenticationFilter reads two parameters): 204 on success,
                // the one neutral 401 on every failure. The manager refuses over-long fields before anything is hashed.
                .authenticationManager(new LoginFieldCapAuthenticationManager(authenticationManager))
                .formLogin(login -> login.loginPage(LOGIN_PATH).loginProcessingUrl(LOGIN_PATH)
                        .successHandler(loggedIn()).failureHandler(invalidCredentials(problems)))
                // POST only (CSRF is on), ends the session, expires the cookie and answers 204.
                .logout(logout -> logout.logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID"))
                // A pre-login session id is replaced by a new one on login (changeSessionId): fixation.
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                // CsrfFilter reports a missing or invalid token to this same access-denied handler.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticated(problems))
                        .accessDeniedHandler(forbidden(problems)))
                // A saved request would open a session (and set a cookie) for every anonymous 401.
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.disable()))
                .build();
    }

    /**
     * Last resort: no matcher, deny everything, no session, no cookie. Anonymous requests get 401 and authenticated
     * ones 403. CSRF is off because nothing here ever accepts a request, so there is no state to protect and no token
     * cookie to hand out.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain adminFallbackDenyChain(HttpSecurity http, ProblemResponses problems) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticated(problems))
                        .accessDeniedHandler(forbidden(problems)))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .headers(headers -> headers.httpStrictTransportSecurity(hsts -> hsts.disable()))
                .build();
    }

    private static AuthenticationSuccessHandler loggedIn() {
        return (request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    /** Every authentication failure is the same response: unknown account, wrong password, over-long field. */
    private static AuthenticationFailureHandler invalidCredentials(ProblemResponses problems) {
        return (request, response, exception) -> problems.write(request, response, SecurityProblem.INVALID_CREDENTIALS);
    }

    private static AuthenticationEntryPoint unauthenticated(ProblemResponses problems) {
        return (request, response, exception) -> problems.write(request, response, SecurityProblem.UNAUTHENTICATED);
    }

    private static AccessDeniedHandler forbidden(ProblemResponses problems) {
        return (request, response, exception) -> problems.write(request, response, SecurityProblem.FORBIDDEN);
    }
}
