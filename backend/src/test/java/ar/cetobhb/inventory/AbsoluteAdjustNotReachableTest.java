package ar.cetobhb.inventory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.CetoBackendApplication;
import ar.cetobhb.inventory.service.InventoryService;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * D-20261008-10: the API never sets an absolute stock value. The absolute {@code InventoryService.adjust} stays for
 * internal use, but nothing outside the inventory service may call it, so no controller (and no admin facade) can
 * reach it; admin code uses the relative {@code adjustBy}.
 */
class AbsoluteAdjustNotReachableTest {

    private static ArchRule rule(String allowedPackage) {
        return noClasses().that().resideOutsideOfPackage(allowedPackage)
                .should().callMethod(InventoryService.class, "adjust", long.class, int.class)
                .as("only " + allowedPackage + " may call the absolute InventoryService.adjust");
    }

    /** A caller outside the inventory service: the rule must notice it. */
    static class OutsideCaller {
        void call(InventoryService inventory) {
            inventory.adjust(1L, 5);
        }
    }

    @Test
    void noProductionClassOutsideTheInventoryServiceCallsTheAbsoluteAdjust() {
        var production = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackagesOf(CetoBackendApplication.class);

        assertThatCode(() -> rule("..inventory.service..").check(production)).doesNotThrowAnyException();
    }

    @Test
    void theRuleBitesWhenSomeoneElseCallsIt() {
        var fixture = new ClassFileImporter().importClasses(OutsideCaller.class);

        assertThatThrownBy(() -> rule("..inventory.service..").check(fixture)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("adjust");
    }
}
