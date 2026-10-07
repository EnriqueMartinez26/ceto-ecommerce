package ar.cetobhb.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves each rule bites: it must fail on a deliberately violating fixture and pass on a compliant one.
 * Fixtures live in test sources only; {@link ArchitectureTest} never sees them (DoNotIncludeTests).
 */
class ArchitectureRulesTest {

    private static final String FIXTURES = "ar.cetobhb.architecture.fixtures.";

    private static JavaClasses fixtures(String subPackage) {
        return new ClassFileImporter().importPackages(FIXTURES + subPackage);
    }

    @Test
    void domainPurityRuleFailsWhenDomainDependsOnJpa() {
        assertThatThrownBy(() -> ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE.check(fixtures("domainimpure")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ImpureDomain")
                .hasMessageContaining("jakarta.persistence");
    }

    @Test
    void layeringRuleFailsWhenControllerReachesRepository() {
        assertThatThrownBy(() -> ArchitectureRules.LAYERS.check(fixtures("layering")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BadController")
                .hasMessageContaining("BadRepository");
    }

    @Test
    void transactionalRuleFailsOutsideServicePackages() {
        assertThatThrownBy(() -> ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES.check(fixtures("tx")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("TxOutsideService");
    }

    @Test
    void clockRuleFailsOnEverySystemClockAccess() {
        assertThatThrownBy(() -> ArchitectureRules.NO_SYSTEM_CLOCK.check(fixtures("clock")))
                .isInstanceOf(AssertionError.class)
                .satisfies(error -> {
                    for (String call : List.of(
                            "Instant.now()", "LocalDateTime.now()", "LocalDate.now()",
                            "ZonedDateTime.now()", "OffsetDateTime.now()",
                            "Clock.systemDefaultZone()", "ZoneId.systemDefault()")) {
                        assertThat(error.getMessage()).as(call).contains(call);
                    }
                });
    }

    @Test
    void compliantCodeSatisfiesEveryRule() {
        JavaClasses clean = fixtures("clean");
        List<ArchRule> rules = List.of(
                ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE,
                ArchitectureRules.LAYERS,
                ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES,
                ArchitectureRules.NO_SYSTEM_CLOCK);
        for (ArchRule rule : rules) {
            assertThatCode(() -> rule.check(clean)).doesNotThrowAnyException();
        }
    }
}
