package ar.cetobhb.admin.domain;

import java.util.List;

/**
 * Rows of a capped admin read plus whether the cap cut the result short, so a caller can never mistake a truncated list
 * for the complete one. Built from a fetch of {@code cap + 1} rows: the extra row only proves there was more.
 *
 * @param rows      at most {@code cap} rows, in the order of the query
 * @param truncated true when at least one more row existed beyond the cap
 */
public record CappedList<T>(List<T> rows, boolean truncated) {

    public CappedList {
        rows = List.copyOf(rows);
    }

    /**
     * Trims {@code fetched} (which holds at most {@code cap + 1} rows) to {@code cap} rows.
     *
     * @throws IllegalArgumentException when {@code cap} is below 1 or {@code fetched} holds more than {@code cap + 1}
     */
    public static <T> CappedList<T> fromFetchOfCapPlusOne(List<T> fetched, int cap) {
        if (cap < 1 || fetched.size() > (long) cap + 1) {
            throw new IllegalArgumentException("a capped read must fetch at most cap + 1 rows (cap " + cap + ")");
        }
        boolean truncated = fetched.size() > cap;
        return new CappedList<>(truncated ? fetched.subList(0, cap) : fetched, truncated);
    }
}
