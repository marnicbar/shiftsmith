package dev.shiftsmith.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The skill catalogue (issue #47): one row per skill an employee can have and a
 * shift can require. Replaces {@code Settings.skills}. {@code ordinal} preserves
 * the catalogue's display order from the legacy list.
 */
@Entity
@Table(name = "skill")
public class SkillEntity extends TimestampedEntity {

    @Id
    @Column(name = "name", length = 255)
    public String name;

    @Column(name = "ordinal", nullable = false)
    public int ordinal;
}
