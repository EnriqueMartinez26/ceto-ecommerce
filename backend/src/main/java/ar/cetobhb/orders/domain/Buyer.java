package ar.cetobhb.orders.domain;

/**
 * Buyer data of a checkout request, as received (D-20261006-10: name, phone, address and zone only). The
 * string form hides personal data so it never reaches logs.
 */
public record Buyer(String name, String phone, String address, ShippingZone zone) {

    @Override
    public String toString() {
        return "Buyer[redacted, zone=" + zone + "]";
    }
}
