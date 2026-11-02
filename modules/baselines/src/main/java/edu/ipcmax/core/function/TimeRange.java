package edu.ipcmax.core.function;

/**
 * Closed-open time interval {@code [startMinute, endMinute)} over the day grid.
 */
public record TimeRange(double startMinute, double endMinute) {
    /**
     * Creates a validated time range.
     */
    public TimeRange {
        if (!Double.isFinite(startMinute) || !Double.isFinite(endMinute)) {
            throw new IllegalArgumentException("time range bounds must be finite");
        }
        if (endMinute < startMinute) {
            throw new IllegalArgumentException("time range end must be >= start");
        }
    }

    /**
     * Returns true when this range has no duration.
     */
    public boolean isEmpty() {
        return endMinute == startMinute;
    }

    /**
     * Returns true when {@code minute} lies in {@code [start,end)}.
     */
    public boolean contains(double minute) {
        return minute >= startMinute && minute < endMinute;
    }

    /**
     * Returns true when this range overlaps another range.
     */
    public boolean overlaps(TimeRange other) {
        return startMinute < other.endMinute && other.startMinute < endMinute;
    }
}
