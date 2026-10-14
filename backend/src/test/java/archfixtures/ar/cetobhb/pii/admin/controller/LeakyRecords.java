package archfixtures.ar.cetobhb.pii.admin.controller;

/** Violating fixtures: records with personal components whose toString prints them. */
public final class LeakyRecords {

    private LeakyRecords() {
    }

    /** No override: javac's generated toString prints every component. */
    public record NoOverride(long id, String buyerName) {
    }

    /** An override that still prints the phone. */
    public record OverrideThatLeaks(long id, String phone) {

        @Override
        public String toString() {
            return "OverrideThatLeaks[id=" + id + ", phone=" + phone + "]";
        }
    }

    /** The outer record is fine; the nested one leaks. */
    public record Outer(long id) {

        public record Line(int lineNo, String productName) {
        }

        @Override
        public String toString() {
            return "Outer[id=" + id + "]";
        }
    }
}
