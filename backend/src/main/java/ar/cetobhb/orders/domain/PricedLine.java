package ar.cetobhb.orders.domain;

import java.math.BigDecimal;

/** A unit price read from the database and the requested quantity. */
public record PricedLine(BigDecimal unitPrice, int quantity) {}
