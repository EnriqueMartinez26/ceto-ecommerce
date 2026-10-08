package ar.cetobhb.inventory;

import static com.tngtech.archunit.base.DescribedPredicate.*;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.CetoBackendApplication;
import ar.cetobhb.inventory.service.InventoryService;
import archfixtures.ar.cetobhb.inventory.service.SiblingServiceCaller;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * D-20261008-10: the API never sets an absolute stock value. The absolute {@code InventoryService.adjust} stays for
 * internal use, but nothing except {@code InventoryService} itself may call it, so no controller, no admin facade and
 * no sibling class of the same package can reach it; admin code uses the relative {@code adjustBy}.
 */
class AbsoluteAdjustNotReachableTest {

    /**
     * Uses {@code accessTargetWhere} on purpose: {@code callMethod} does not see method references
     * ({@code inventory::adjust}), while an access target check covers calls and references alike. {@code adjust} has
     * one overload today; a new one would be covered too, since the match is by name.
     */
    private static ArchRule rule() {
        return noClasses().that().doNotHaveFullyQualifiedName(InventoryService.class.getName())
                .should().accessTargetWhere(target(declaredIn(InventoryService.class)).and(target(name("adjust"))))
                .as("only InventoryService itself may call the absolute InventoryService.adjust");
    }

    /** A caller outside the inventory service: the rule must notice it. */
    static class OutsideCaller {
        void call(InventoryService inventory) {
            inventory.adjust(1L, 5);
        }
    }

    /** A caller that only holds a method reference: the rule must notice it as well. */
    static class MethodReferenceCaller {
        BiConsumer<Long, Integer> reference(InventoryService inventory) {
            return inventory::adjust;
        }
    }

    @Test
    void noProductionClassOtherThanTheInventoryServiceCallsTheAbsoluteAdjust() {
        var production = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackagesOf(CetoBackendApplication.class);

        assertThatCode(() -> rule().check(production)).doesNotThrowAnyException();
    }

    @Test
    void theRuleBitesWhenSomeoneElseCallsIt() {
        var fixture = new ClassFileImporter().importClasses(OutsideCaller.class);

        assertThatThrownBy(() -> rule().check(fixture)).isInstanceOf(AssertionError.class)
                .hasMessageContaining(OutsideCaller.class.getName());
    }

    @Test
    void theRuleBitesOnAMethodReference() {
        var fixture = new ClassFileImporter().importClasses(MethodReferenceCaller.class);

        assertThatThrownBy(() -> rule().check(fixture)).isInstanceOf(AssertionError.class)
                .hasMessageContaining(MethodReferenceCaller.class.getName());
    }

    @Test
    void theRuleBitesOnASiblingClassOfTheInventoryServicePackage() {
        var fixture = new ClassFileImporter().importClasses(SiblingServiceCaller.class);

        assertThatThrownBy(() -> rule().check(fixture)).isInstanceOf(AssertionError.class)
                .hasMessageContaining(SiblingServiceCaller.class.getName());
    }
}
