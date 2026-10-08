package ar.cetobhb.admin;

import static ar.cetobhb.support.CatalogFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.catalog.domain.ProductAdminView;
import ar.cetobhb.support.AbstractPostgresIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ACAT-01: the admin list shows every product, inactive and combos included, by id with the combo link. */
class AdminCatalogListIT extends AbstractPostgresIT {

    @Autowired
    private AdminCatalogService admin;

    @Test
    void listsInactiveBasesAndCombosByIdWithTheirComboLink() {
        long zeta = admin.createBase(product("Zeta", "10.00")).id();
        long alfa = admin.createBase(product("Alfa", "20.00")).id();
        long combo = admin.createCombo(product("Combo", "55.00"), alfa, 3).id();
        admin.deactivate(alfa);
        admin.deactivate(combo);

        List<ProductAdminView> list = admin.list().rows();

        assertThat(list).extracting(ProductAdminView::id).containsExactly(zeta, alfa, combo);
        assertThat(list).extracting(ProductAdminView::active).containsExactly(true, false, false);
        ProductAdminView listedCombo = list.get(2);
        assertThat(listedCombo.isCombo()).isTrue();
        assertThat(listedCombo.comboBaseProductId()).isEqualTo(alfa);
        assertThat(listedCombo.comboBaseUnits()).isEqualTo(3);
        assertThat(list.get(0).isCombo()).isFalse();
        assertThat(list.get(0).version()).isZero();
    }
}
