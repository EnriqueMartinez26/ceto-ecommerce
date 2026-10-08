package ar.cetobhb.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import ar.cetobhb.common.config.ClockConfig;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

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
     * controller -> service -> repository. All three layers are required (neither withOptionalLayers nor
     * allowEmptyShould): real controllers exist (StorefrontController, ApiExceptionHandler), so a rename or move that
     * empties a layer must fail instead of passing silently. Compliant fixtures therefore hold all three layers.
     * JPA entities live in {@code <feature>.repository} (AD-1), so they sit inside the repository layer.
     */
    static final ArchRule LAYERS = layeredArchitecture().consideringAllDependencies()
            .layer("Controller").definedBy(CONTROLLERS)
            .layer("Service").definedBy(SERVICES)
            .layer("Repository").definedBy(REPOSITORIES)
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service");

    private static final ArchRule NO_TRANSACTIONAL_CLASSES_OUTSIDE_SERVICES = noClasses()
            .that().resideOutsideOfPackage(SERVICES)
            .should().beAnnotatedWith(Transactional.class);

    /**
     * Narrow exemption: a repository method may carry @Transactional only with propagation MANDATORY, i.e. to
     * require a transaction opened by the service (row-lock queries are meaningless without one) and never to open
     * one itself. Any other propagation, or MANDATORY outside a repository package, still fails.
     */
    private static final ArchCondition<JavaMethod> BE_IN_SERVICE_OR_MANDATORY_IN_REPOSITORY =
            new ArchCondition<>("be declared in a ..service.. package, or in a ..repository.. package with"
                    + " propagation MANDATORY") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    JavaClass owner = method.getOwner();
                    boolean inService = resideInAPackage(SERVICES).test(owner);
                    boolean mandatoryInRepository = resideInAPackage(REPOSITORIES).test(owner)
                            && method.getAnnotationOfType(Transactional.class).propagation() == Propagation.MANDATORY;
                    if (!inService && !mandatoryInRepository) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " is @Transactional outside a service package (repositories may only use MANDATORY) in "
                                + method.getSourceCodeLocation()));
                    }
                }
            };

    // Not allowEmptyShould: CatalogService carries @Transactional methods, so a rename or move
    // that empties this rule must fail instead of passing silently.
    private static final ArchRule NO_TRANSACTIONAL_METHODS_OUTSIDE_SERVICES = methods()
            .that().areAnnotatedWith(Transactional.class)
            .should(BE_IN_SERVICE_OR_MANDATORY_IN_REPOSITORY);

    static final ArchRule TRANSACTIONAL_ONLY_IN_SERVICES = CompositeArchRule
            .of(NO_TRANSACTIONAL_CLASSES_OUTSIDE_SERVICES)
            .and(NO_TRANSACTIONAL_METHODS_OUTSIDE_SERVICES)
            .as("@Transactional is only allowed in ..service.. packages (repositories: propagation MANDATORY only)");

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

    /**
     * CLAUDE.md section 4, rule 5: an entity changes state through intent methods (markPaid, cancel, ...), never a
     * public setter, so status transitions cannot bypass the graph. Not allowEmptyShould: Order and InventoryStock
     * exist, so a rename or move that empties the rule must fail instead of passing silently.
     */
    static final ArchRule ENTITIES_HAVE_NO_PUBLIC_SETTERS = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class)
            .and().arePublic()
            .should().haveNameMatching("set[A-Z].*")
            .as("JPA entities expose no public setters; change state through intent methods");

    /** Spring's {@code @Transactional} is the only one allowed; the Jakarta one has different semantics. */
    static final ArchRule NO_JAKARTA_TRANSACTIONAL = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.transaction.Transactional")
            .as("jakarta.transaction.Transactional is banned; use org.springframework.transaction.annotation.Transactional");

    /**
     * AD-2: a feature's repository package is reached only by the same feature's service (or repository)
     * package. The feature is the package prefix before the {@code service}/{@code repository} segment, so the
     * rule works for any root package. Not allowEmptyShould: the catalog repository exists, so a rename or move
     * that empties this rule must fail instead of passing silently.
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
            .as("a feature's ..repository.. is accessed only by the same feature's ..service..");

    private static final String[] WEB_API_PACKAGES = {
            "jakarta.servlet..", "org.springframework.web..", "org.springframework.http.."};

    /**
     * Controllers and advice live in {@code ..controller..} packages (AD-H1), so the HTTP surface is found in one kind
     * of place and the endpoint inventory has a single source.
     */
    static final ArchRule WEB_TYPES_ONLY_IN_CONTROLLER_PACKAGES = classes()
            .that().areMetaAnnotatedWith(Controller.class).or().areMetaAnnotatedWith(ControllerAdvice.class)
            .should().resideInAPackage(CONTROLLERS)
            .as("@Controller, @RestController and @(Rest)ControllerAdvice classes reside in ..controller.. packages");

    /**
     * Services and domain classes know nothing of HTTP: no servlet API, no Spring web or http types. The translation
     * from HTTP to a core call and back is the controller's job.
     */
    static final ArchRule SERVICES_AND_DOMAIN_ARE_WEB_FREE = noClasses()
            .that().resideInAnyPackage(SERVICES, DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(WEB_API_PACKAGES)
            .as("..service.. and ..domain.. classes must not depend on the servlet API or on Spring web/http types");

    /**
     * Same-origin by design (D-20261007-14, REQ-HRD-04): no {@code @CrossOrigin}, no use of Spring's CORS types and no
     * {@code addCorsMappings} override. A CORS grant would have to be a conscious change of this rule.
     */
    static final ArchRule NO_CROSS_ORIGIN = CompositeArchRule
            .of(noClasses().should().dependOnClassesThat().haveFullyQualifiedName(CrossOrigin.class.getName())
                    .orShould().dependOnClassesThat().resideInAPackage("org.springframework.web.cors..")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName(
                            "org.springframework.web.servlet.config.annotation.CorsRegistry"))
            .and(noMethods().should().haveName("addCorsMappings"))
            .as("no @CrossOrigin, no CORS configuration types, no addCorsMappings");

    /**
     * Annotated controllers are the only way to expose an endpoint: functional endpoints ({@code RouterFunction},
     * {@code ServerResponse}) would escape the annotation-based ArchUnit input rules and the HTTP inventory
     * (REQ-HRD-03).
     */
    static final ArchRule NO_FUNCTIONAL_ENDPOINTS = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.servlet.function..", "org.springframework.web.reactive.function.server..")
            .as("no functional endpoints: no RouterFunction, HandlerFunction or ServerResponse");

    private static final Set<String> NUMERIC_TYPES =Set.of("byte", "short", "int", "long", "float", "double",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
            "java.lang.Double", "java.math.BigDecimal", "java.math.BigInteger");
    private static final String BV = "jakarta.validation.constraints.";
    private static final String HV_RANGE = "org.hibernate.validator.constraints.Range";
    private static final Set<String> LOWER_BOUNDS = Set.of(BV + "Min", BV + "DecimalMin", BV + "Positive",
            BV + "PositiveOrZero", HV_RANGE);
    private static final Set<String> UPPER_BOUNDS = Set.of(BV + "Max", BV + "DecimalMax", BV + "Negative",
            BV + "NegativeOrZero", HV_RANGE);
    private static final Set<String> LENGTH_BOUNDS = Set.of(BV + "Size", "org.hibernate.validator.constraints.Length");
    /** Packages whose classes are not request DTOs: they are not walked for fields. */
    private static final List<String> FOREIGN_PACKAGES = List.of("java.", "javax.", "jakarta.", "org.", "com.", "tools.");

    /**
     * Everything a client controls must be bounded (CLAUDE.md section 5, REQ-HRD-05): every numeric handler parameter
     * and every numeric component of a {@code @RequestBody} type (walked through nested DTOs and collections) declares
     * a minimum and a maximum; every string or collection declares {@code @Size(max)}. Un-annotated simple parameters
     * of a handler are query parameters and count too.
     */
    static final ArchRule REQUEST_INPUTS_ARE_BOUNDED = methods()
            .that().areMetaAnnotatedWith(RequestMapping.class)
            .should(new ArchCondition<JavaMethod>("have every client-controlled input bounded (numbers with min and"
                    + " max, strings and collections with @Size(max))") {
                @Override
                public void check(JavaMethod handler, ConditionEvents events) {
                    for (JavaParameter parameter : handler.getParameters()) {
                        String where = handler.getFullName() + " parameter " + parameter.getIndex();
                        if (hasAnnotation(parameter.getAnnotations(), RequestBody.class.getName())) {
                            walkBody(parameter.getRawType(), where, new HashSet<>(), handler, events);
                        } else if (isInput(parameter.getRawType())) {
                            report(unboundedReason(parameter.getRawType(), parameter.getAnnotations()), where,
                                    handler, events);
                        }
                    }
                }
            });

    private static void walkBody(JavaClass type, String path, Set<String> seen, JavaMethod handler,
            ConditionEvents events) {
        if (FOREIGN_PACKAGES.stream().anyMatch(type.getName()::startsWith) || type.isPrimitive()
                || !seen.add(type.getName())) {
            return;
        }
        for (JavaField field : type.getFields()) {
            if (field.getModifiers().contains(JavaModifier.STATIC)) {
                continue;
            }
            String fieldPath = path + " -> " + type.getSimpleName() + "." + field.getName();
            if (isInput(field.getRawType())) {
                report(unboundedReason(field.getRawType(), field.getAnnotations()), fieldPath, handler, events);
            }
            if (!NUMERIC_TYPES.contains(field.getRawType().getName())) {
                for (JavaClass involved : field.getType().getAllInvolvedRawTypes()) {
                    walkBody(involved, fieldPath, seen, handler, events);
                }
            }
        }
    }

    private static boolean isInput(JavaClass type) {
        return NUMERIC_TYPES.contains(type.getName()) || type.isEquivalentTo(String.class)
                || type.isAssignableTo(Collection.class);
    }

    /** What is missing on an input, or null when it is bounded. */
    private static String unboundedReason(JavaClass type, Set<? extends JavaAnnotation<?>> annotations) {
        if (NUMERIC_TYPES.contains(type.getName())) {
            boolean lower = annotations.stream().anyMatch(a -> LOWER_BOUNDS.contains(a.getRawType().getName()));
            boolean upper = annotations.stream().anyMatch(a -> UPPER_BOUNDS.contains(a.getRawType().getName()));
            if (lower && upper) {
                return null;
            }
            return "is numeric and lacks " + (lower ? "a maximum" : upper ? "a minimum" : "a minimum and a maximum");
        }
        boolean sized = annotations.stream().anyMatch(a -> LENGTH_BOUNDS.contains(a.getRawType().getName())
                && a.tryGetExplicitlyDeclaredProperty("max").isPresent());
        return sized ? null : "is a string or collection and lacks @Size(max = ...)";
    }

    private static void report(String reason, String where, JavaMethod handler, ConditionEvents events) {
        if (reason != null) {
            events.add(SimpleConditionEvent.violated(handler, where + " " + reason + " in "
                    + handler.getSourceCodeLocation()));
        }
    }

    private static boolean hasAnnotation(Set<? extends JavaAnnotation<?>> annotations, String name) {
        return annotations.stream().anyMatch(a -> a.getRawType().getName().equals(name));
    }

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
