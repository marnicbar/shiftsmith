package dev.shiftsmith.persistence.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;

/**
 * A job/role (issue #47), replacing the {@code Position} document node. Its shift
 * templates are normalized into {@code shift_template} rows keyed by
 * {@code position_id}; the required-skill set is kept inline.
 */
@Entity
@Table(name = "position")
public class PositionEntity extends TimestampedEntity {

    @Id
    @Column(name = "id", length = 255)
    public String id;

    @Column(name = "name")
    public String name;

    @Column(name = "color", nullable = false)
    public int color;

    /** "group" is a reserved word; the column is named {@code grp}. */
    @Column(name = "grp")
    public String group;

    @ElementCollection
    @CollectionTable(name = "position_skill", joinColumns = @JoinColumn(name = "position_id"))
    @Column(name = "skill")
    public Set<String> skills = new HashSet<>();
}
