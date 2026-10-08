package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;

/**
 * Fail-fast scoping (review fix batch 5): the Security filter is re-scoped to {@code /api/admin/*} by a
 * post-processor that finds the registration by type and target name. If Boot ever renames or stops creating that
 * registration, the filter would silently keep Boot's {@code /*} mapping (or Security would vanish), so the context
 * must refuse to start unless exactly one registration was rewritten.
 */
class SecurityFilterScopeFailFastTest {

    private static final String SECURITY_TARGET = "springSecurityFilterChain";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(SecurityFilterScopeConfig.class);

    private static DelegatingFilterProxyRegistrationBean registration(String target) {
        return new DelegatingFilterProxyRegistrationBean(target);
    }

    @Test
    void startsAndRewritesTheOneSecurityRegistration() {
        runner.withBean("securityRegistration", DelegatingFilterProxyRegistrationBean.class,
                () -> registration(SECURITY_TARGET)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DelegatingFilterProxyRegistrationBean.class).getUrlPatterns())
                            .containsExactly("/api/admin/*");
                });
    }

    @Test
    void failsToStartWhenNoSecurityRegistrationExists() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly one");
        });
    }

    @Test
    void failsToStartWhenOnlyOtherRegistrationsExist() {
        runner.withBean("other", DelegatingFilterProxyRegistrationBean.class, () -> registration("someOtherChain"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("exactly one");
                });
    }

    @Test
    void failsToStartWhenTwoSecurityRegistrationsExist() {
        runner.withBean("first", DelegatingFilterProxyRegistrationBean.class, () -> registration(SECURITY_TARGET))
                .withBean("second", DelegatingFilterProxyRegistrationBean.class,
                        () -> registration(SECURITY_TARGET))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("exactly one");
                });
    }
}
