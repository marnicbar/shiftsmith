package dev.shiftsmith.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Singleton solver configuration row (issue #47): replaces the {@code horizon*}
 * fields of the legacy {@code Settings} document. The skill catalogue and global
 * working-time rules that also lived on {@code Settings} are normalized into the
 * {@code skill} and {@code work_rule} (global-scope) tables respectively.
 */
@Entity
@Table(name = "settings")
public class SettingsEntity extends TimestampedEntity {

    /** There is only ever one settings row; it always lives under this id. */
    public static final Long SINGLETON_ID = 1L;

    @Id
    public Long id;

    @Column(name = "horizon_unit", nullable = false, length = 16)
    public String horizonUnit;

    @Column(name = "horizon_count", nullable = false)
    public int horizonCount;
}
