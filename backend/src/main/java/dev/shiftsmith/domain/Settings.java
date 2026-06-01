package dev.shiftsmith.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * Solver configuration set from the Settings page.
 *
 * The solve window starts at the beginning of today and extends to the
 * beginning of the next full {@code horizonUnit}, plus {@code horizonCount}
 * more units. So with unit=week, count=1 the window covers "this week and the
 * next"; with unit=day, count=1 it covers "today and tomorrow".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {

    private String horizonUnit = "week"; // "day" | "week" | "month"
    private int horizonCount = 1;

    public Settings() {}

    public Settings(String horizonUnit, int horizonCount) {
        this.horizonUnit = horizonUnit;
        this.horizonCount = horizonCount;
    }

    /** Inclusive first day of the solve window: the start of today. */
    public LocalDate horizonStart(LocalDate today) {
        return today;
    }

    /** Exclusive last day of the solve window. */
    public LocalDate horizonEnd(LocalDate today) {
        int count = Math.max(1, horizonCount);
        return switch (horizonUnit == null ? "week" : horizonUnit) {
            case "day" -> today.plusDays(1).plusDays(count);
            case "month" -> today.withDayOfMonth(1).plusMonths(1).plusMonths(count);
            default -> { // week (Monday-based, matching the frontend)
                LocalDate nextWeek = today.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
                yield nextWeek.plusWeeks(count);
            }
        };
    }

    public String getHorizonUnit() { return horizonUnit; }
    public void setHorizonUnit(String horizonUnit) { this.horizonUnit = horizonUnit; }

    public int getHorizonCount() { return horizonCount; }
    public void setHorizonCount(int horizonCount) { this.horizonCount = horizonCount; }
}
