package dev.shiftsmith.auth;

import jakarta.enterprise.context.RequestScoped;

/**
 * The authenticated principal for the current request (issue #47, Phase 6),
 * populated by {@link AuthFilter} and injected by resources to authorize writes.
 * A {@code manager}/{@code admin} has full access; an {@code employee} may only
 * touch the calendar of the person it is linked to.
 *
 * <p>State is exposed through methods, not public fields: this is a normal-scoped
 * (request) CDI bean, so it is injected as a client proxy — and a proxy delegates
 * method calls to the real contextual instance but does <em>not</em> intercept
 * field access. Using accessors keeps every reader/writer on the same instance.
 */
@RequestScoped
public class CurrentUser {

    private String username;
    private String role;        // admin | manager | employee
    private String employeeId;  // the linked person, or null

    public void set(String username, String role, String employeeId) {
        this.username = username;
        this.role = role;
        this.employeeId = employeeId;
    }

    public String username() { return username; }
    public String role() { return role; }
    public String employeeId() { return employeeId; }

    public boolean isManager() {
        return "admin".equals(role) || "manager".equals(role);
    }

    public boolean isEmployee() {
        return "employee".equals(role);
    }

    /** May this user edit {@code targetEmployeeId}'s calendar? */
    public boolean canEditCalendar(String targetEmployeeId) {
        if (isManager()) return true;
        return isEmployee() && targetEmployeeId != null && targetEmployeeId.equals(employeeId);
    }
}
