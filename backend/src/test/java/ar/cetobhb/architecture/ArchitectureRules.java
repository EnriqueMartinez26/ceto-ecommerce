package ar.cetobhb.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import ar.cetobhb.common.config.ClockConfig;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

/** Rule definitions shared by the real-code check and the fixture-based "rule bites" proof. */
final class ArchitectureRules {

    private static final String CONTROLLERS = "..controller..";
    private static final String SERVICES = "..service..";
    private static final String REPOSITORIES = "..repository..";
    private static final String DOMAIN = "..domain..";
    private static final String CLOCK_CONFIG_NAMES = Pattern.quote(ClockConfig.class.getName()) + "(\\$.*)?";

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

    /** A {@code java.time} {@code now} overload other than {@code now(Clock)}: it reads the system clock or zone. */
    private static final DescribedPredicate<JavaMethodCall> NON_DETERMINISTIC_NOW =
            new DescribedPredicate<>("call a java.time now overload that does not take a Clock") {
                @Override
                public boolean test(JavaMethodCall call) {
                    List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
                    boolean takesClock = parameters.size() == 1 && parameters.get(0).isEquivalentTo(Clock.class);
                    return call.getTarget().getName().equals("now")
                            && call.getTargetOwner().getPackageName().startsWith("java.time")
                            && !takesClock;
                }
            };

    /**
     * {@code new Date()} reads the clock; {@code new Date(long)} does not. {@code new GregorianCalendar()} and the
     * {@code TimeZone}/{@code Locale} constructors also read system time; only the explicit-field constructors
     * ({@code int} year, month, day...) are allowed.
     */
    private static final DescribedPredicate<JavaConstructorCall> SYSTEM_TIME_DATE_OR_GREGORIAN_CALENDAR =
            new DescribedPredicate<>("call a Date or GregorianCalendar constructor that reads system time") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    List<JavaClass> parameters = call.getTarget().getRawParameterTypes();
                    if (call.getTargetOwner().isEquivalentTo(Date.class)) {
                        return parameters.isEmpty();
                    }
                    return call.getTargetOwner().isEquivalentTo(GregorianCalendar.class)
                            && (parameters.isEmpty() || parameters.stream().anyMatch(
                            type -> type.isEquivalentTo(TimeZone.class) || type.isEquivalentTo(Locale.class)));
                }
            };

    /**
     * CLAUDE.md rule 21 (REQ-GRD-06): only ClockConfig (and its inner and lambda classes) may touch the system
     * clock or zone. Bans every {@code now} overload on {@code java.time} classes except {@code now(Clock)},
     * the {@code Clock.system*} factories, {@code ZoneId.systemDefault}, {@code TimeZone.getDefault},
     * {@code System.currentTimeMillis}, the no-arg {@code Date} constructor, the {@code GregorianCalendar}
     * constructors that take no argument, a {@code TimeZone} or a {@code Locale}, and {@code Calendar.getInstance}.
     * Allowed: the injected {@code Clock} ({@code clock.instant()}, {@code X.now(clock)},
     * {@code X.now(clock.withZone(...))}), {@code new Date(long)} and the explicit-field {@code GregorianCalendar}
     * constructors. This is a best-effort guard for the common entry points, not a proof that no code can reach the
     * system clock (for example through other libraries or reflection).
     */
    static final ArchRule NO_SYSTEM_CLOCK = noClasses()
            .that().haveNameNotMatching(CLOCK_CONFIG_NAMES)
            .should().callMethodWhere(NON_DETERMINISTIC_NOW)
            .orShould().callMethodWhere(target(owner(type(Clock.class))).and(target(nameMatching("system.*"))))
            .orShould().callMethod(ZoneId.class, "systemDefault")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(TimeZone.class, "getDefault")
            .orShould().callConstructorWhere(SYSTEM_TIME_DATE_OR_GREGORIAN_CALENDAR)
            .orShould().callMethodWhere(target(owner(type(Calendar.class))).and(target(name("getInstance"))))
            .as("the system clock and zone are read only in ClockConfig; inject Clock everywhere else");

    /** Spring's {@code @Transactional} is the only one allowed; the Jakarta one has different semantics. */
    static final ArchRule NO_JAKARTA_TRANSACTIONAL = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.transaction.Transactional")
            .as("jakarta.transaction.Transactional is banned; use org.springframework.transaction.annotation.Transactional");

    /**
     * AD-2: a feature's repository package is reached only by the same feature's service (or repository)
     * package. The feature is the package prefix before the {@code service}/{@code repository} segment, so the
     * rule works for any root package. allowEmptyShould(true) until the first repository lands; tracked: drop it
     * then so a rename or move cannot silently empty the rule.
     */
    static final ArchRule REPOSITORY_ONLY_FROM_OWN_FEATURE_SERVICE = classes()
            .that().resideInAPackage(REPOSITORIES)
            .should(new ArchCondition<JavaClass>("only be accessed from the service or repository package of the same feature") {
                @Override
                public void check(JavaClass repositoryClass, ConditionEvents events) {
                    String feature = featureOf(repositoryClass.getPackageName());
                    for (Dependency dependency : repositoryClass.getDirectDependenciesToSelf()) {
                        JavaClass origin = dependency.getOriginClass();
                        if (origin.equals(repositoryClass) || origin.getPackageName().equals(repositoryClass.getPackageName())) {
                            continue;
                        }
                        boolean allowed = feature.equals(featureOf(origin.getPackageName()))
                                && isLayerPackage(origin.getPackageName(), "service", "repository");
                        if (!allowed) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                    }
                }
            })
            .allowEmptyShould(true)
            .as("a feature's ..repository.. is accessed only by the same feature's ..service..");

    private static final Pattern LAYER_PACKAGE = Pattern.compile("^(.*?)\\.(service|repository|controller|domain)(\\..*)?$");

    /** Package prefix before the first layer segment, or the whole package when it has none. */
    private static String featureOf(String packageName) {
        Matcher matcher = LAYER_PACKAGE.matcher(packageName);
        return matcher.matches() ? matcher.group(1) : packageName;
    }

    private static boolean isLayerPackage(String packageName, String... layers) {
        Matcher matcher = LAYER_PACKAGE.matcher(packageName);
        return matcher.matches() && List.of(layers).contains(matcher.group(2));
    }
}
