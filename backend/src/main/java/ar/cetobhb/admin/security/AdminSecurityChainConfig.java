package ar.cetobhb.admin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Skeleton of the administration chain (slice X1): every request under {@code /api/admin/} needs an authenticated
 * principal and an anonymous one gets a bare 401. It replaces Boot's default chain, which would otherwise answer for
 * {@code /*} with form login and a generated password. Login, CSRF, problem bodies and the session policy arrive in
 * the following slices; until then nothing can authenticate, so every admin path is closed.
 */
@Configuration(proxyBeanMethods = false)
class AdminSecurityChainConfig {

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/admin/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // A saved request would open a session (and set a cookie) for every anonymous 401.
                .requestCache(cache -> cache.disable())
                .build();
    }
}
