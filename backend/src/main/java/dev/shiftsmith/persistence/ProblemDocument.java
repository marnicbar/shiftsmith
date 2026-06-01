package dev.shiftsmith.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;

import java.util.List;
import java.util.Map;

/**
 * The canonical problem definition as persisted to the database.
 *
 * <p>This is the whole editable problem (employees, positions, settings and the
 * manual assignment overrides) — i.e. everything the frontend syncs via
 * {@code PUT /api/problem}. It is stored as a single JSONB document rather than
 * normalised tables: the model is deeply nested, the whole problem is synced
 * atomically, and several domain classes carry Timefold planning annotations
 * that don't mix well with JPA-managed entities.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProblemDocument {
    public List<Employee> employees;
    public List<Position> positions;
    public Settings settings;
    public Map<String, List<String>> overrides;
}
