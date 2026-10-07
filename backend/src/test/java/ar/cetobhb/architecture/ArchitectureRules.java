package ar.cetobhb.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import ar.cetobhb.common.config.ClockConfig;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.transaction.annotation.Transactional;

/** Rule definitions shared by the real-code check and the fixture-based "rule bites" proof. */
final class ArchitectureRules {

    private static final String CONTROLLERS = "..controller..";
    private static final String SERVICES = "..service..";
    private static final String REPOSITORIES = "..repository..";
    private static final String DOMAIN = "..domain..";

    private ArchitectureRules() {
    }

    /**
     * controller -> service -> repository. Layers are optional because the first feature packages arrive in
     * later changes; allowEmptyShould keeps the rule green until then. Tracked for the HTTP change: once real
     * controllers exist, drop allowEmptyShould(true) so a rename or move cannot silently empty the rule.
     * JPA entities live in {@code <feature>.repository} (AD-1), so they sit inside the repository layer.
     */
    static final ArchRule LAYERS = layeredArchitecture().consideringAllDependencies()
            .layer("Controller").definedBy(CONTROLLERS)
            .layer("Service").definedBy(SERVICES)
            .layer("Repository").definedBy(REPOSITORIES)
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .withOptionalLayers(true)
            .allowEmptyShould(true);

    private static final ArchRule NO_TRANSACTIONAL_CLASSES_OUTSIDE_SERVICES = noClasses()
            .that().resideOutsideOfPackage(SERVICES)
            .should().beAnnotatedWith(Transactional.class);

    private static final ArchRule NO_TRANSACTIONAL_METHODS_OUTSIDE_SERVICES = noMethods()
            .that().areAnnotatedWith(Transactional.class)
            .should().beDeclaredInClassesThat().resideOutsideOfPackage(SERVICES)
            // No @Transactional methods exist yet; tracked: drop once the first service lands (HTTP/service changes).
            .allowEmptyShould(true);

    static final ArchRule TRANSACTIONAL_ONLY_IN_SERVICES = CompositeArchRule
            .of(NO_TRANSACTIONAL_CLASSES_OUTSIDE_SERVICES)
            .and(NO_TRANSACTIONAL_METHODS_OUTSIDE_SERVICES)
            .as("@Transactional is only allowed in ..service.. packages");

    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..")
            .as("domain classes must not depend on Spring, JPA or Hibernate");

    /** CLAUDE.md rule 21 (REQ-GRD-06): only ClockConfig may touch the system clock or zone. */
    static final ArchRule NO_SYSTEM_CLOCK = noClasses()
            .that().doNotHaveFullyQualifiedName(ClockConfig.class.getName())
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(Clock.class, "systemDefaultZone")
            .orShould().callMethod(ZoneId.class, "systemDefault")
            .as("the system clock and zone are read only in ClockConfig; inject Clock everywhere else");
}
