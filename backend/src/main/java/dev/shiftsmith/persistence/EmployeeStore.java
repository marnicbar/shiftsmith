package dev.shiftsmith.persistence;

import dev.shiftsmith.domain.Block;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Rule;
import dev.shiftsmith.persistence.entity.AvailabilityBlockEntity;
import dev.shiftsmith.persistence.entity.EmployeeEntity;
import dev.shiftsmith.persistence.entity.WorkRuleEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.Optional;

/**
 * Granular, concurrency-safe writes for a single employee (issue #47, Phase 4):
 * create / replace / delete one {@code employee} aggregate (the row plus its skills,
 * availability blocks and personal rules) without rewriting the whole problem.
 *
 * <p>Each write carries an expected {@code version}; a mismatch is a {@link Result#CONFLICT}
 * ({@code 409}) so two clients editing the same person can't silently clobber each other,
 * while edits to <em>different</em> people never conflict. A successful write bumps the
 * version, returned to the caller as the new ETag. Serialization with the rest of the
 * problem state is handled by the caller ({@code ScheduleService}); here each operation
 * is one transaction, so the load-check-write is atomic at the row level.
 */
@ApplicationScoped
public class EmployeeStore {

    public enum Result { OK, NOT_FOUND, CONFLICT, DUPLICATE }

    /** The outcome of a write: its status and (on success) the employee's new version. */
    public record Outcome(Result result, long version) {
        static Outcome of(Result r) { return new Outcome(r, 0); }
        static Outcome ok(long version) { return new Outcome(Result.OK, version); }
    }

    /** Current version of an employee, or empty if there is no such row. */
    @Transactional
    public Optional<Long> versionOf(String id) {
        EmployeeEntity e = EmployeeEntity.findById(id);
        return e == null ? Optional.empty() : Optional.of(e.version);
    }

    /** Every employee's current version, keyed by id (for the snapshot's ETag map). */
    @Transactional
    public java.util.Map<String, Long> allVersions() {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        for (EmployeeEntity e : EmployeeEntity.<EmployeeEntity>listAll()) out.put(e.id, e.version);
        return out;
    }

    @Transactional
    public Outcome create(Employee emp) {
        if (EmployeeEntity.findById(emp.getId()) != null) return Outcome.of(Result.DUPLICATE);
        EmployeeEntity e = new EmployeeEntity();
        e.id = emp.getId();
        e.version = 0;
        applyScalar(e, emp);
        e.persist();
        writeChildren(emp);
        return Outcome.ok(0);
    }

    @Transactional
    public Outcome update(Employee emp, long expectedVersion) {
        EmployeeEntity e = EmployeeEntity.findById(emp.getId());
        if (e == null) return Outcome.of(Result.NOT_FOUND);
        if (e.version != expectedVersion) return Outcome.of(Result.CONFLICT);

        applyScalar(e, emp);
        e.version = expectedVersion + 1; // bump on any successful write to the aggregate
        // Replace this person's blocks and personal rules wholesale (they're owned by the
        // employee and small); global rules and everyone else's rows are untouched.
        AvailabilityBlockEntity.delete("employeeId", emp.getId());
        WorkRuleEntity.delete("employeeId", emp.getId());
        writeChildren(emp);
        return Outcome.ok(e.version);
    }

    /**
     * Replace just one employee's availability blocks (issue #47, Phase 6) — the
     * calendar an employee account may self-edit, without touching manager-controlled
     * fields (skills/role/contract). Bumps the employee version. No If-Match: a calendar
     * edit is last-write-wins.
     */
    @Transactional
    public Outcome updateAvailability(String id, java.util.List<Block> blocks) {
        EmployeeEntity e = EmployeeEntity.findById(id);
        if (e == null) return Outcome.of(Result.NOT_FOUND);
        e.version = e.version + 1;
        AvailabilityBlockEntity.delete("employeeId", id);
        for (Block b : blocks) ProblemMapper.blockToEntity(b, id).persist();
        return Outcome.ok(e.version);
    }

    /** Replace just one employee's personal working-time rules (issue #47, Phase 6). */
    @Transactional
    public Outcome updateRules(String id, java.util.List<Rule> rules) {
        EmployeeEntity e = EmployeeEntity.findById(id);
        if (e == null) return Outcome.of(Result.NOT_FOUND);
        e.version = e.version + 1;
        WorkRuleEntity.delete("employeeId", id);
        for (Rule r : rules) ProblemMapper.ruleToEntity(r, id).persist();
        return Outcome.ok(e.version);
    }

    @Transactional
    public Outcome delete(String id, long expectedVersion) {
        EmployeeEntity e = EmployeeEntity.findById(id);
        if (e == null) return Outcome.of(Result.NOT_FOUND);
        if (e.version != expectedVersion) return Outcome.of(Result.CONFLICT);
        e.delete(); // cascades skills/blocks/rules; SET NULL on its assignment rows
        return Outcome.ok(expectedVersion);
    }

    private static void applyScalar(EmployeeEntity e, Employee emp) {
        e.firstName = emp.getFirstName();
        e.lastName = emp.getLastName();
        e.role = emp.getRole();
        e.contract = emp.getContract();
        e.color = emp.getColor();
        e.skills = emp.getSkills() == null ? new HashSet<>() : new HashSet<>(emp.getSkills());
    }

    private static void writeChildren(Employee emp) {
        for (Block b : emp.getBlocks()) ProblemMapper.blockToEntity(b, emp.getId()).persist();
        for (Rule r : emp.getRules()) ProblemMapper.ruleToEntity(r, emp.getId()).persist();
    }
}
