package dev.shiftsmith.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;

import java.util.List;
import java.util.Map;

/** Bulk problem payload synced from the frontend. Any null field is left unchanged. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProblemDTO {
    public List<Employee> employees;
    public List<Position> positions;
    public Settings settings;
    public Map<String, List<String>> overrides;
}
