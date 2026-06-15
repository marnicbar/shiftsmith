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
 * A schedulable person (issue #47), replacing the {@code Employee} document node.
 * Availability blocks and personal working-time rules are normalized into their
 * own interval-queryable tables ({@code availability_block}, {@code work_rule})
 * keyed by {@code employee_id}; only the catalogue-style skill set is kept inline
 * as an element collection.
 */
@Entity
@Table(name = "employee")
public class EmployeeEntity extends TimestampedEntity {

    @Id
    @Column(name = "id", length = 255)
    public String id;

    @Column(name = "first_name")
    public String firstName;

    @Column(name = "last_name")
    public String lastName;

    @Column(name = "role")
    public String role;

    @Column(name = "contract", nullable = false)
    public int contract;

    @Column(name = "color", nullable = false)
    public int color;

    @ElementCollection
    @CollectionTable(name = "employee_skill", joinColumns = @JoinColumn(name = "employee_id"))
    @Column(name = "skill")
    public Set<String> skills = new HashSet<>();
}
