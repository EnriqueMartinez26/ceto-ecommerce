package ar.cetobhb.checkout.controller;

import jakarta.validation.constraints.Size;

/** Buyer data as typed by the client (D-20261006-10). Blank or missing values are judged by the normalizer. */
record BuyerRequest(@Size(max = 1000, message = "BUYER_NAME_TOO_LONG") String name,
        @Size(max = 64, message = "INVALID_PHONE") String phone,
        @Size(max = 2000, message = "ADDRESS_TOO_LONG") String address,
        @Size(max = 32, message = "INVALID_ZONE") String zone) {
}
