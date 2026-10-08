package ar.cetobhb.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractServerIT;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.multipart.MultipartResolver;

/**
 * No route reads a multipart body, so the container must not be configured to parse one: with
 * {@code spring.servlet.multipart.enabled=false} Boot defines no {@code MultipartResolver} and registers the dispatcher
 * servlet without a multipart configuration (review fix batch 7). The login guard also refuses a multipart login
 * with 415 ({@link AdminLoginRequestGuardServerIT}).
 */
class MultipartDisabledServerIT extends AbstractServerIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void multipartIsDisabledInTheEffectiveConfiguration() {
        assertThat(environment.getProperty("spring.servlet.multipart.enabled", Boolean.class)).isFalse();
    }

    @Test
    void thereIsNoMultipartResolverAndTheDispatcherServletHasNoMultipartConfig() {
        assertThat(context.getBeansOfType(MultipartResolver.class)).isEmpty();
        Collection<DispatcherServletRegistrationBean> registrations = context
                .getBeansOfType(DispatcherServletRegistrationBean.class).values();
        assertThat(registrations).as("the dispatcher servlet registration exists").isNotEmpty();
        assertThat(registrations).allSatisfy(registration -> assertThat(registration.getMultipartConfig()).isNull());
    }
}
