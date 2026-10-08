package ar.cetobhb.admin.security;

import ar.cetobhb.common.web.ProblemResponses;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link AdminAccessGuardInterceptor} for every handler under {@code /api/admin/}. */
@Configuration(proxyBeanMethods = false)
class AdminAccessGuardConfig implements WebMvcConfigurer {

    private final ProblemResponses problems;

    AdminAccessGuardConfig(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAccessGuardInterceptor(problems))
                .addPathPatterns(AdminAccessGuardInterceptor.PATH_PATTERN);
    }
}
