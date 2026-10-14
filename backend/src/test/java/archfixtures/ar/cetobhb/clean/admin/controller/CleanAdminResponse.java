package archfixtures.ar.cetobhb.clean.admin.controller;

import java.util.List;

/** Compliant control: personal components are hidden in toString, the nested record too. */
public record CleanAdminResponse(long id, String buyerName, String buyerPhone, List<Line> lines) {

    public record Line(int lineNo, String productName) {

        @Override
        public String toString() {
            return "Line[lineNo=" + lineNo + "]";
        }
    }

    @Override
    public String toString() {
        return "CleanAdminResponse[id=" + id + "]";
    }
}
