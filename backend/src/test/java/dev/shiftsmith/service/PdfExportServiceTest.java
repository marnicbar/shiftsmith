package dev.shiftsmith.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shiftsmith.domain.Employee;
import dev.shiftsmith.domain.Position;
import dev.shiftsmith.domain.ShiftTemplate;
import dev.shiftsmith.export.CalendarDocumentBuilder;
import dev.shiftsmith.export.ExportDocument;
import dev.shiftsmith.export.ExportRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Renders real documents through the real {@code typst} binary when one is on PATH,
 * and otherwise checks that the service degrades with a clear message rather than
 * blowing up. CI images without Typst therefore still run green — the export endpoint
 * is the only thing that needs it.
 */
class PdfExportServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 7, 27);

    private static PdfExportService service(String bin) {
        PdfExportService s = new PdfExportService();
        s.typstBin = bin;
        s.timeoutSeconds = 30;
        s.mapper = new ObjectMapper();
        return s;
    }

    /** A small but representative problem: two positions, an overnight shift, an open slot. */
    private static ExportDocument doc(String view, String... scopes) {
        Employee anna = employee("e1", "Anna", "Müller", 0);
        Employee ben = employee("e2", "Ben", "Ott", 1);
        Position kitchen = position("p1", "Kitchen", 2,
                template("s1", MON, 480, 960, 2),
                template("s2", MON.plusDays(1), 1320, 360, 1)); // overnight
        Position bar = position("p2", "Bar", 5, template("s3", MON.plusDays(2), 1020, 1380, 2));
        ExportRequest req = ExportRequest.of(List.of(scopes), view, MON, 0, 1440,
                "a4", "landscape", "en", "first");
        Map<String, List<String>> assign = Map.of(
                "s1@2026-07-27", List.of("e1", "e2"),
                "s2@2026-07-28", List.of("e1"),
                "s3@2026-07-29", List.of("e2")); // one slot short → "1 open"
        return new CalendarDocumentBuilder(req, List.of(anna, ben), List.of(kitchen, bar),
                assign, LocalDateTime.of(2026, 7, 27, 14, 3)).build();
    }

    private static Employee employee(String id, String first, String last, int colour) {
        Employee e = new Employee();
        e.setId(id);
        e.setFirstName(first);
        e.setLastName(last);
        e.setColor(colour);
        e.setSkills(new LinkedHashSet<>());
        return e;
    }

    private static ShiftTemplate template(String id, LocalDate date, int start, int end, int headcount) {
        ShiftTemplate t = new ShiftTemplate();
        t.setId(id);
        t.setDate(date);
        t.setStart(start);
        t.setEnd(end);
        t.setHeadcount(headcount);
        t.setRepeat("none");
        return t;
    }

    private static Position position(String id, String name, int colour, ShiftTemplate... shifts) {
        Position p = new Position();
        p.setId(id);
        p.setName(name);
        p.setColor(colour);
        p.setSkills(Set.of());
        p.setShifts(new ArrayList<>(List.of(shifts)));
        return p;
    }

    private static boolean typstAvailable() {
        try {
            Process p = new ProcessBuilder("typst", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void templateIsOnTheClasspath() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("typst/calendar.typ")) {
            assertThat(in).as("typst/calendar.typ must ship with the app").isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("json(\"data.json\")");
        }
    }

    @Test
    void rendersEveryViewToAPdf() {
        assumeTrue(typstAvailable(), "typst binary not on PATH");
        for (String view : new String[] { "day", "week", "month" }) {
            byte[] pdf = service("typst").render(doc(view, "position:p1"));
            assertThat(pdf).as(view + " view").startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
            assertThat(pdf.length).isGreaterThan(1000);
        }
    }

    @Test
    void rendersAPersonPageAndABatchOfSeveral() {
        assumeTrue(typstAvailable(), "typst binary not on PATH");
        assertThat(service("typst").render(doc("week", "person:e1")))
                .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

        ExportDocument batch = doc("week", "person:e1", "person:e2", "position:p1", "position:p2");
        assertThat(batch.sections()).hasSize(4);
        byte[] pdf = service("typst").render(batch);
        assertThat(pdf).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        // Four sections page-break into four pages; the batch is meaningfully bigger.
        assertThat(pdf.length).isGreaterThan(service("typst").render(doc("week", "person:e1")).length);
    }

    @Test
    void rendersTheDroppedShiftFootnote() {
        assumeTrue(typstAvailable(), "typst binary not on PATH");
        // A 06:00–12:00 band leaves the evening Bar shift off the page entirely.
        ExportRequest req = ExportRequest.of(List.of("position:p2"), "week", MON, 360, 720,
                "a4", "landscape", "en", "first");
        Employee ben = employee("e2", "Ben", "Ott", 1);
        Position bar = position("p2", "Bar", 5, template("s3", MON.plusDays(2), 1020, 1380, 1));
        ExportDocument d = new CalendarDocumentBuilder(req, List.of(ben), List.of(bar),
                Map.of("s3@2026-07-29", List.of("e2")), LocalDateTime.of(2026, 7, 27, 14, 3)).build();
        assertThat(d.sections().get(0).dropped().count()).isEqualTo(1);
        assertThat(service("typst").render(d)).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void reportsAMissingBinaryInsteadOfCrashing() {
        assertThatThrownBy(() -> service("definitely-not-typst").render(doc("week", "position:p1")))
                .isInstanceOf(PdfExportService.ExportException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsAnEmptyDocument() {
        assertThatThrownBy(() -> service("typst").render(null))
                .isInstanceOf(PdfExportService.ExportException.class)
                .hasMessageContaining("no sections");
    }

    @Test
    void cleansUpItsScratchDirectory() throws IOException {
        assumeTrue(typstAvailable(), "typst binary not on PATH");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = countScratchDirs(tmp);
        service("typst").render(doc("week", "position:p1"));
        assertThat(countScratchDirs(tmp)).isEqualTo(before);
    }

    private static long countScratchDirs(Path tmp) throws IOException {
        try (var s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith("shiftsmith-export-")).count();
        }
    }
}
