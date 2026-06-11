package dev.shiftsmith.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * The catalogue of skills employees can have and shifts can require (managed on the Settings page).
     * Starts empty on a fresh database — new installations have no skills until they are added.
     */
    private List<String> skills = new ArrayList<>();

    /**
     * Global working-time rules that apply to everyone (managed on the Settings page).
     * They act as defaults: an employee inherits a global rule unless they define
     * their own (stricter) rule for the same metric+op. Starts empty on a fresh
     * database — new installations have no global rules until they are added.
     */
    private List<Rule> globalRules = new ArrayList<>();

    public Settings() {}

    public Settings(String horizonUnit, int horizonCount) {
        this.horizonUnit = horizonUnit;
        this.horizonCount = horizonCount;
    }

    /** Inclusive first day of the solve window: the start of today. */
    public LocalDate horizonStart(LocalDate today) {
        return today;
    }

    /**
     * Hard ceiling on the solve window length, in days, regardless of unit — about
     * two years (covering leap days). The day-by-day expansion loops once per day in
     * the window, so this bounds the work no matter how the unit/count are set.
     */
    public static final int MAX_HORIZON_DAYS = 732;

    /** Exclusive last day of the solve window, clamped to {@link #MAX_HORIZON_DAYS}. */
    public LocalDate horizonEnd(LocalDate today) {
        // Clamp to a sane span even if a legacy/corrupt document slipped past the
        // API validator, so the day-by-day expansion loop can never run away.
        LocalDate raw = rawHorizonEnd(today);
        LocalDate cap = horizonStart(today).plusDays(MAX_HORIZON_DAYS);
        return raw.isAfter(cap) ? cap : raw;
    }

    /** Exclusive end of the window as configured, before the safety cap is applied. */
    LocalDate rawHorizonEnd(LocalDate today) {
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

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills == null ? new ArrayList<>() : skills; }

    public List<Rule> getGlobalRules() { return globalRules; }
    public void setGlobalRules(List<Rule> globalRules) { this.globalRules = globalRules == null ? new ArrayList<>() : globalRules; }
}
