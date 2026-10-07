package ar.cetobhb.checkout.controller;

import java.util.List;

/** Envelope of the list, so the contract can grow without breaking clients. */
record StorefrontListResponse(List<StorefrontProductResponse> products) {
}
