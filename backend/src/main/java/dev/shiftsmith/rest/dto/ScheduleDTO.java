package dev.shiftsmith.rest.dto;

import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.Settings;
import dev.shiftsmith.domain.ShiftAssignment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Full state returned to the frontend, including the solver's current best assignment. */
public class ScheduleDTO {

    /** One staffed (or empty) slot. Frontend groups these by {@code shiftTemplateId + "@" + date}. */
    public record Slot(String id, String positionId, String shiftTemplateId, int slotIndex,
                       LocalDate date, LocalDateTime start, LocalDateTime end,
                       String employeeId, boolean pinned) {
        public static Slot of(ShiftAssignment a) {
            return new Slot(a.getId(), a.getPositionId(), a.getShiftTemplateId(), a.getSlotIndex(),
                    a.getDate(), a.getStart(), a.getEnd(),
                    a.getEmployee() == null ? null : a.getEmployee().getId(), a.isPinned());
        }
    }

    public record Score(long hard, long medium, long soft) {}

    public List<Employee> employees;
    public List<Position> positions;
    public Settings settings;
    public Map<String, List<String>> overrides;
    public List<Slot> assignments;
    public String solverStatus;
    public Score score;
    public LocalDate horizonStart;
    public LocalDate horizonEnd;
    public int total;
    public int staffed;
    public int unassigned;
}
