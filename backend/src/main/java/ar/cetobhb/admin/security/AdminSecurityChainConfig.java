package ar.cetobhb.admin.security;

import ar.cetobhb.admin.config.AdminProperties;
import ar.cetobhb.common.domain.TokenBucketLimiter;
import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * The administration chains (slice X1 skeleton, hardened in review fix batch 5, problem bodies in slice X3, login and
 * logout in slice X4, session policy in slice X5): every request under {@code /api/admin/} needs an authenticated
 * principal, except {@code GET /api/admin/session} and the login. An anonymous one gets a 401 {@code UNAUTHENTICATED}
 * problem and a denied one (including a missing or invalid CSRF token) a 403 {@code FORBIDDEN} problem, both written by
 * {@link ProblemResponses} so they match the advice's shape and carry the request id and hardening headers that
 * filters ahead of Security already set. They replace Boot's default chain, which would otherwise answer for
 * {@code /*} with form login and a generated password.
 *
 * <p>Session policy: a login replaces the session id, a newer login ends the older session (one administrator session,
 * fed by the session events of the container), and the idle timeout is set per session by the success handler.
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

    /**
     * The CSRF cookie. The {@code __Host-} prefix makes the browser accept it only when it is Secure, has Path=/ and no
     * Domain, so a sibling subdomain cannot plant a token. The header the SPA echoes stays X-XSRF-TOKEN.
     */
    static final String CSRF_COOKIE_NAME = "__Host-XSRF-TOKEN";

    /**
     * Publishes the session events of the container to Spring, which is how the registry learns that a session was
     * invalidated or timed out. Boot does not register it. Without it a destroyed session would stay in the registry
     * (REQ-AUTH-04), so it is pinned by tests that read the registry after a logout and an expiry.
     */
    @Bean
    HttpSessionEventPublisher adminHttpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /** The registry of the live administrator sessions (single session). A bean so that a test can read it. */
    @Bean
    SessionRegistry adminSessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** The admin chain is consulted first. */
    static final int ADMIN_CHAIN_ORDER = 0;

    @Bean
    @Order(ADMIN_CHAIN_ORDER)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, ProblemResponses problems,
            @Qualifier("adminAuthenticationManager") AuthenticationManager authenticationManager,
            SessionRegistry adminSessionRegistry, AdminProperties admin, Clock clock) throws Exception {
        // The default repository keeps the token in the HTTP session, which would open a server session (and set a
        // JSESSIONID) for every anonymous POST. The cookie repository keeps the token on the client and the server
        // stateless until someone logs in. The cookie is readable by the SPA (that is how it echoes it back), Secure
        // and SameSite=Strict.
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieName(CSRF_COOKIE_NAME);
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));
        return http
                .securityMatcher("/api/admin/**")
                // The one anonymous allowance: GET /api/admin/session, matched exactly (method and path as written).
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(new SessionBootstrapRequest()).permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                // A session older than ceto.admin.session-absolute-timeout-minutes ends however active it is.
                .addFilterAfter(new AbsoluteSessionTimeoutFilter(clock,
                        Duration.ofMinutes(admin.sessionAbsoluteTimeoutMinutes()), problems), HeaderWriterFilter.class)
                // A session that a newer login superseded is dropped silently for the requests that open or close a
                // session (the bootstrap must still answer, and deliver the XSRF cookie); any other request keeps the 401.
                .addFilterAfter(new SupersededSessionFilter(adminSessionRegistry), HeaderWriterFilter.class)
                // The login request is shaped (no query string, urlencoded, one value per field) before CSRF reads it.
                .addFilterBefore(new LoginRequestGuardFilter(LOGIN_PATH, problems), CsrfFilter.class)
                // FIRST of the three login filters: every login POST spends one token of its address's bucket, whatever
                // it carries, before the shape guard, CSRF or the authentication manager see it. A limited attempt
                // therefore costs no BCrypt, and a flood of malformed or token-less attempts is bounded too.
                .addFilterBefore(new AdminLoginRateLimitFilter(loginLimiter(clock, admin.loginRateLimit()), LOGIN_PATH,
                        problems), LoginRequestGuardFilter.class)
                // The login is a form post (UsernamePasswordAuthenticationFilter reads two parameters): 204 on success,
                // the one neutral 401 on every failure. The manager refuses over-long fields before anything is hashed.
                .authenticationManager(new LoginFieldCapAuthenticationManager(authenticationManager))
                .formLogin(login -> login.loginPage(LOGIN_PATH).loginProcessingUrl(LOGIN_PATH)
                        .successHandler(new AdminLoginSuccessHandler(admin.sessionIdleTimeoutMinutes(), clock))
                        .failureHandler(invalidCredentials(problems)))
                // POST only (CSRF is on), ends the session, expires the cookie and answers 204.
                .logout(logout -> logout.logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID"))
                // A pre-login session id is replaced by a new one on login (changeSessionId): fixation.
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId())
                        // One administrator session at a time: a newer login expires the older one, whose next request
                        // gets the 401 problem (the default strategy writes a plain-text 200 body).
                        .maximumSessions(1).sessionRegistry(adminSessionRegistry)
                        .expiredSessionStrategy(event -> problems.write(event.getRequest(), event.getResponse(),
                                SecurityProblem.UNAUTHENTICATED)))
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

    /** Per-address bucket of the login (REQ-AUTH-10); the bounds are validated in {@link AdminProperties}. */
    static TokenBucketLimiter loginLimiter(Clock clock, AdminProperties.LoginRateLimit limit) {
        return new TokenBucketLimiter(clock, limit.burst(), Duration.ofSeconds(limit.refillIntervalSeconds()),
                limit.maxTrackedClients(), limit.overflowBurst(), limit.overflowRefillInterval());
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
