package ar.cetobhb.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.common.config.WebProperties;
import ar.cetobhb.support.AbstractHttpIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.util.unit.DataSize;

/**
 * Container-side limits that complement {@link BodySizeLimitFilter}: the filter counts what the application reads,
 * but Tomcat reads some bodies by itself (form parameters, and the unread body it swallows to reuse a connection).
 * Both must be bounded by the same order of magnitude as the application limit.
 */
class BodyLimitContainerConfigIT extends AbstractHttpIT {

    @Autowired
    private TomcatServerProperties tomcat;

    @Autowired
    private WebProperties web;

    @Test
    void tomcatSwallowsAtMost64KbOfAnUnreadBodyBeforeClosingTheConnection() {
        assertThat(tomcat.getMaxSwallowSize()).isEqualTo(DataSize.ofKilobytes(64));
    }

    @Test
    void tomcatParsesNoMoreFormBytesThanTheApplicationBodyLimit() {
        assertThat(tomcat.getMaxHttpFormPostSize().toBytes()).isLessThanOrEqualTo(web.maxBodyBytes());
    }
}
