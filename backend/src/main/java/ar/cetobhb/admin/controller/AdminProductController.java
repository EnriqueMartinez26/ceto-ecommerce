package ar.cetobhb.admin.controller;

import ar.cetobhb.admin.service.AdminCatalogService;
import ar.cetobhb.catalog.domain.NewProduct;
import ar.cetobhb.catalog.domain.ProductEditData;
import ar.cetobhb.catalog.domain.ProductSnapshot;
import ar.cetobhb.common.domain.ReasonCode;
import ar.cetobhb.common.domain.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administration catalog routes (ACAT-01 to ACAT-07). HTTP only: bounds, the shape of a creation and the mapping
 * to the facade; the name, price and combo rules, the version check and the audit row are decided below.
 * Authentication and CSRF are the job of the Security chain (every route under {@code /api/admin/} is protected by the
 * filter scope), and the response is never cached by it. There is no delete route: a product leaves the sale by being
 * deactivated.
 */
@RestController
@RequestMapping(path = "/api/admin/products", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminProductController {

    private final AdminCatalogService catalog;

    AdminProductController(AdminCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    AdminProductListResponse list() {
        return AdminProductListResponse.from(catalog.list());
    }

    /** A BASE carries no link and a COMBO needs both the base and the units; any other pairing is INVALID_COMBO_LINK. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AdminProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        NewProduct data = new NewProduct(request.name(), request.price(), request.compareAtPrice(), null, null, null);
        boolean combo = request.type() == ProductType.COMBO;
        boolean complete = request.baseProductId() != null && request.units() != null;
        boolean linked = request.baseProductId() != null || request.units() != null;
        if (combo ? !complete : linked) {
            throw new ValidationException(ReasonCode.INVALID_COMBO_LINK);
        }
        ProductSnapshot created = combo
                ? catalog.createCombo(data, request.baseProductId(), request.units())
                : catalog.createBase(data);
        return AdminProductResponse.fromCreated(created);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    AdminProductResponse update(@PathVariable @Min(1) @Max(AdminBounds.MAX_ID) long id,
            @Valid @RequestBody UpdateProductRequest request) {
        return AdminProductResponse.from(catalog.update(id, request.version(),
                new ProductEditData(request.name(), request.price(), request.compareAtPrice())));
    }

    @PostMapping("/{id}/activate")
    ProductActivationResponse activate(@PathVariable @Min(1) @Max(AdminBounds.MAX_ID) long id) {
        return new ProductActivationResponse(id, true, catalog.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    ProductActivationResponse deactivate(@PathVariable @Min(1) @Max(AdminBounds.MAX_ID) long id) {
        return new ProductActivationResponse(id, false, catalog.deactivate(id));
    }
}
