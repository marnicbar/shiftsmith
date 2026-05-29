package dev.shiftsmith.service;

import ai.timefold.solver.core.api.solver.SolverManager;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.EmployeeSchedule;
import dev.shiftsmith.domain.Shift;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ScheduleService {

    private static final String JOB_ID = "MAIN";

    @Inject
    SolverManager<EmployeeSchedule> solverManager;

    private final List<Employee> employees = new ArrayList<>();
    private final List<Shift> shifts = new ArrayList<>();
    private volatile EmployeeSchedule bestSolution;

    @PostConstruct
    void init() {
        seedDemoData();
    }

    // --- Schedule access ---

    public EmployeeSchedule getSchedule() {
        EmployeeSchedule solution = bestSolution;
        if (solution == null) {
            solution = new EmployeeSchedule(new ArrayList<>(employees), new ArrayList<>(shifts));
        }
        solution.setSolverStatus(solverManager.getSolverStatus(JOB_ID));
        return solution;
    }

    // --- Solver lifecycle ---

    public synchronized void startSolving() {
        try {
            solverManager.terminateEarly(JOB_ID);
        } catch (Exception ignored) {}
        bestSolution = null;
        solverManager.solveAndListen(
                JOB_ID,
                new EmployeeSchedule(new ArrayList<>(employees), new ArrayList<>(shifts)),
                solution -> this.bestSolution = solution
        );
    }

    public void stopSolving() {
        solverManager.terminateEarly(JOB_ID);
    }

    // --- Employee CRUD ---

    public synchronized void addEmployee(Employee employee) {
        employees.add(employee);
    }

    public synchronized void updateEmployee(String name, Employee updated) {
        employees.replaceAll(e -> e.getName().equals(name) ? updated : e);
    }

    public synchronized void removeEmployee(String name) {
        employees.removeIf(e -> e.getName().equals(name));
    }

    // --- Shift CRUD ---

    public synchronized void addShift(Shift shift) {
        shift.setId(UUID.randomUUID().toString());
        shifts.add(shift);
    }

    public synchronized void updateShift(String id, Shift updated) {
        updated.setId(id);
        shifts.replaceAll(s -> s.getId().equals(id) ? updated : s);
    }

    public synchronized void removeShift(String id) {
        shifts.removeIf(s -> s.getId().equals(id));
    }

    // --- Demo data ---

    private void seedDemoData() {
        Employee alice = new Employee("Alice", Set.of("Bartender", "Waiter"));
        Employee bob   = new Employee("Bob",   Set.of("Chef"));
        Employee carol = new Employee("Carol", Set.of("Waiter", "Cashier"));
        Employee dave  = new Employee("Dave",  Set.of("Bartender"));
        Employee eve   = new Employee("Eve",   Set.of("Waiter", "Cashier"));
        employees.addAll(List.of(alice, bob, carol, dave, eve));

        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            LocalDate day = today.plusDays(i);
            shifts.add(new Shift(UUID.randomUUID().toString(),
                    day.atTime(8, 0), day.atTime(16, 0), "Main Floor", "Waiter"));
            shifts.add(new Shift(UUID.randomUUID().toString(),
                    day.atTime(16, 0), day.atTime(23, 59), "Main Floor", "Bartender"));
        }
    }
}
