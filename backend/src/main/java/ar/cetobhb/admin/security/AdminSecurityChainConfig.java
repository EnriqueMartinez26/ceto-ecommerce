package ar.cetobhb.admin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * The administration chains (slice X1 skeleton, hardened in review fix batch 5): every request under
 * {@code /api/admin/} needs an authenticated principal and an anonymous one gets a bare 401. They replace Boot's
 * default chain, which would otherwise answer for {@code /*} with form login and a generated password. Login, problem
 * bodies and the session policy arrive in the following slices; until then nothing can authenticate, so every admin
 * path is closed.
 *
 * <p>Two chains, in this order: the admin chain, which matches {@code /api/admin/**}, and a fallback with no matcher
 * that denies everything. The servlet filter mapping ({@link SecurityFilterScopeConfig}) lets only {@code /api/admin/*}
 * reach Security, so the fallback never sees a public route; it exists so that an admin path the admin chain's matcher
 * fails to cover is refused instead of reaching a handler with no authentication.
 */
@Configuration(proxyBeanMethods = false)
class AdminSecurityChainConfig {

    /** The admin chain is consulted first. */
    static final int ADMIN_CHAIN_ORDER = 0;

    @Bean
    @Order(ADMIN_CHAIN_ORDER)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        // The default repository keeps the token in the HTTP session, which would open a server session (and set a
        // JSESSIONID) for every anonymous POST. The cookie repository keeps the token on the client and the server
        // stateless until someone logs in. The cookie is readable by the SPA (that is how it echoes it back), Secure
        // and SameSite=Strict.
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict").path("/"));
        return http
                .securityMatcher("/api/admin/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // A saved request would open a session (and set a cookie) for every anonymous 401.
                .requestCache(cache -> cache.disable())
                .build();
    }

    /**
     * Last resort: no matcher, deny everything, no session, no cookie. Anonymous requests get 401 and authenticated
     * ones 403. CSRF is off because nothing here ever accepts a request, so there is no state to protect and no token
     * cookie to hand out.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain adminFallbackDenyChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .build();
    }
}
