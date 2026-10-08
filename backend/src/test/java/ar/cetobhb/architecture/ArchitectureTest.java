package ar.cetobhb.architecture;

import ar.cetobhb.CetoBackendApplication;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the rules on the real production code (CLAUDE.md section 3 and rule 21). */
@AnalyzeClasses(packagesOf = CetoBackendApplication.class, importOptions = DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers = ArchitectureRules.LAYERS;

    @ArchTest
    static final ArchRule transactionalOnlyInServices = ArchitectureRules.TRANSACTIONAL_ONLY_IN_SERVICES;

    @ArchTest
    static final ArchRule domainIsFrameworkFree = ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE;

    @ArchTest
    static final ArchRule noSystemClock = ArchitectureRules.NO_SYSTEM_CLOCK;

    @ArchTest
    static final ArchRule noJakartaTransactional = ArchitectureRules.NO_JAKARTA_TRANSACTIONAL;

    @ArchTest
    static final ArchRule entitiesHaveNoPublicSetters = ArchitectureRules.ENTITIES_HAVE_NO_PUBLIC_SETTERS;

    @ArchTest
    static final ArchRule webTypesOnlyInControllerPackages = ArchitectureRules.WEB_TYPES_ONLY_IN_CONTROLLER_PACKAGES;

    @ArchTest
    static final ArchRule servicesAndDomainAreWebFree = ArchitectureRules.SERVICES_AND_DOMAIN_ARE_WEB_FREE;

    @ArchTest
    static final ArchRule noCrossOrigin = ArchitectureRules.NO_CROSS_ORIGIN;

    @ArchTest
    static final ArchRule requestInputsAreBounded = ArchitectureRules.REQUEST_INPUTS_ARE_BOUNDED;

    @ArchTest
    static final ArchRule repositoryOnlyFromOwnFeatureService =
            ArchitectureRules.REPOSITORY_ONLY_FROM_OWN_FEATURE_SERVICE;
}
