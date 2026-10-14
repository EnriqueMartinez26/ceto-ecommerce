package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.domain.CappedList;
import ar.cetobhb.catalog.domain.ProductAdminView;
import java.util.List;

/** The admin product list: every product by id, active or not, up to the cap; {@code truncated} says the cap cut it. */
record AdminProductListResponse(List<AdminProductResponse> products, boolean truncated) {

    static AdminProductListResponse from(CappedList<ProductAdminView> list) {
        return new AdminProductListResponse(list.rows().stream().map(AdminProductResponse::from).toList(),
                list.truncated());
    }
}
