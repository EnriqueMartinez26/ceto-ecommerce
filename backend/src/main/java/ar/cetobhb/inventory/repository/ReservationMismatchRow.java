package ar.cetobhb.inventory.repository;

/** Interface projection of the reconciliation query; {@code getReserved()} is null when the stock row is missing. */
public interface ReservationMismatchRow {

    Long getProductId();

    Integer getReserved();

    Long getExpected();
}
