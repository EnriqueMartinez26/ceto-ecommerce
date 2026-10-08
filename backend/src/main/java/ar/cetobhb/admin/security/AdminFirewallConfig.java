package ar.cetobhb.admin.security;

import ar.cetobhb.common.web.ProblemResponses;
import ar.cetobhb.common.web.SecurityProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

/**
 * What a request refused by Security's firewall looks like (design AD-A12, verification U-1, U-2). The firewall rejects
 * a path with a matrix parameter, an empty or dot segment, an encoded slash or backslash, before any chain or handler.
 * Left alone, Boot answers a bare 400; here it is the same {@code MALFORMED_REQUEST} problem as an unreadable body, with
 * the request id and hardening headers that the filters ahead of Security already set. It is never a 5xx.
 *
 * <p>Nothing about the request is logged: the exception message names the rejected spelling.
 */
@Configuration(proxyBeanMethods = false)
class AdminFirewallConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminFirewallConfig.class);

    @Bean
    WebSecurityCustomizer rejectedRequestsAsProblems(ProblemResponses problems) {
        return web -> web.requestRejectedHandler((request, response, rejected) -> {
            log.debug("The Security firewall rejected a request");
            problems.write(request, response, SecurityProblem.REJECTED_REQUEST);
        });
    }
}
