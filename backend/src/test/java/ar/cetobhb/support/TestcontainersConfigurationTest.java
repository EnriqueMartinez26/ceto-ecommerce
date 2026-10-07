package ar.cetobhb.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestcontainersConfigurationTest {

    @Test
    void everyConfigurationInstanceHandsOutTheSameContainer() {
        assertThat(new TestcontainersConfiguration().postgres())
                .isSameAs(new TestcontainersConfiguration().postgres());
    }
}
