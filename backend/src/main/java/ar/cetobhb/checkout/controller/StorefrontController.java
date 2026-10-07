package ar.cetobhb.checkout.controller;

import ar.cetobhb.checkout.service.StorefrontService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, read-only catalog (REQ-STO-01). It talks to the service only; query parameters are ignored. */
@RestController
@RequestMapping(path = "/api/store/products", produces = MediaType.APPLICATION_JSON_VALUE)
class StorefrontController {

    /** 2^53 - 1: the largest integer a JavaScript client reads exactly. */
    private static final long MAX_ID = 9_007_199_254_740_991L;

    private final StorefrontService storefront;

    StorefrontController(StorefrontService storefront) {
        this.storefront = storefront;
    }

    @GetMapping
    StorefrontListResponse list() {
        return new StorefrontListResponse(
                storefront.listProducts().stream().map(StorefrontProductResponse::from).toList());
    }

    @GetMapping("/{id}")
    StorefrontProductDetailResponse detail(@PathVariable @Min(1) @Max(MAX_ID) long id) {
        return StorefrontProductDetailResponse.from(storefront.product(id));
    }
}
