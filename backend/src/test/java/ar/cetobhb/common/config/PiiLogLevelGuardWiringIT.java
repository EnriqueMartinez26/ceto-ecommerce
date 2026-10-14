package ar.cetobhb.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.support.AbstractHttpIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** The PII log level guard is part of the REAL application context, not only of the startup test's fixture. */
class PiiLogLevelGuardWiringIT extends AbstractHttpIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void thePiiLogLevelGuardIsPartOfTheApplication() {
        assertThat(context.getBeansOfType(PiiLogLevelGuard.class)).hasSize(1);
    }
}
