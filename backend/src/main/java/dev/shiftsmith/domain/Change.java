package dev.shiftsmith.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * A date-scheduled modification to a {@link Rule}, effective on/after {@code date}.
 * kind "set"    — from {@code date} the rule uses this metric/op/value.
 * kind "remove" — from {@code date} the rule no longer applies.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Change {

    private String id;
    private LocalDate date;
    private String kind;   // "set" | "remove"
    private String metric;
    private String op;
    private int value;

    public Change() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }

    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }
}
