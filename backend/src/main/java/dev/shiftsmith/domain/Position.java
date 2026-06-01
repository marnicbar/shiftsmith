package dev.shiftsmith.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A job/role (Positions view) that owns a set of recurring shift templates. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Position {

    private String id;
    private String name;
    private int color;
    private String group;
    private Set<String> skills = new HashSet<>();
    private List<ShiftTemplate> shifts = new ArrayList<>();

    public Position() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }

    public Set<String> getSkills() { return skills; }
    public void setSkills(Set<String> skills) { this.skills = skills; }

    public List<ShiftTemplate> getShifts() { return shifts; }
    public void setShifts(List<ShiftTemplate> shifts) { this.shifts = shifts; }
}
