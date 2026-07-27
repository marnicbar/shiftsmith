package dev.shiftsmith.rest;

import dev.shiftsmith.export.CalendarDocumentBuilder;
import dev.shiftsmith.export.ExportDocument;
import dev.shiftsmith.export.ExportRequest;
import dev.shiftsmith.rest.dto.ApiError;
import dev.shiftsmith.service.PdfExportService;
import dev.shiftsmith.service.ScheduleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF export of the read-only Personnel / Positions calendars.
 *
 * <p>Two endpoints over the same document build, so they can never disagree:
 * {@code /calendar.pdf} renders it, and {@code /calendar/plan} returns just the
 * metadata the export dialog needs up front — above all which shifts the chosen
 * printed hours would leave off the page.
 *
 * <p>{@code scope} repeats, one per section of the PDF: a single export passes one,
 * and a batch ("everyone, a page each") passes many.
 */
@Path("/api/export")
public class ExportResource {

    private static final Logger LOG = Logger.getLogger(ExportResource.class);

    @Inject
    ScheduleService service;

    @Inject
    PdfExportService pdf;

    @GET
    @Path("/calendar.pdf")
    @Produces("application/pdf")
    public Response calendar(@QueryParam("scope") List<String> scope,
                             @QueryParam("view") String view,
                             @QueryParam("anchor") String anchor,
                             @QueryParam("from") Integer from,
                             @QueryParam("to") Integer to,
                             @QueryParam("paper") String paper,
                             @QueryParam("orientation") String orientation,
                             @QueryParam("lang") String lang,
                             @QueryParam("nameOrder") String nameOrder) {
        ExportDocument doc;
        try {
            doc = build(scope, view, anchor, from, to, paper, orientation, lang, nameOrder);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            byte[] bytes = pdf.render(doc);
            return Response.ok(bytes, "application/pdf")
                    .header("Content-Disposition", "attachment; filename=\"" + filename(doc) + "\"")
                    .build();
        } catch (PdfExportService.ExportException e) {
            LOG.warnf("PDF export failed: %s", e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ApiError(e.getMessage()))
                    .build();
        }
    }

    /** What the export would contain, without rendering it — drives the dialog's warning. */
    @GET
    @Path("/calendar/plan")
    @Produces(MediaType.APPLICATION_JSON)
    public Response plan(@QueryParam("scope") List<String> scope,
                         @QueryParam("view") String view,
                         @QueryParam("anchor") String anchor,
                         @QueryParam("from") Integer from,
                         @QueryParam("to") Integer to,
                         @QueryParam("lang") String lang,
                         @QueryParam("nameOrder") String nameOrder) {
        ExportDocument doc;
        try {
            doc = build(scope, view, anchor, from, to, null, null, lang, nameOrder);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        List<SectionPlan> sections = new ArrayList<>();
        int total = 0;
        for (ExportDocument.Section s : doc.sections()) {
            sections.add(new SectionPlan(s.title(), s.range(), s.segments().size(), s.dropped()));
            total += s.dropped().count();
        }
        return Response.ok(new Plan(sections, total, doc.meta().generated())).build();
    }

    /** A dry run of the export: one entry per page, plus the total shifts left off. */
    public record Plan(List<SectionPlan> sections, int droppedTotal, String generated) {}

    public record SectionPlan(String title, String range, int shifts, ExportDocument.Dropped dropped) {}

    private ExportDocument build(List<String> scope, String view, String anchor,
                                 Integer from, Integer to, String paper, String orientation,
                                 String lang, String nameOrder) {
        ExportRequest req = ExportRequest.of(scope, view, parseDate(anchor), from, to,
                paper, orientation, lang, nameOrder);
        // One range read covers every section, including the lead-in day an overnight
        // shift needs to spill from.
        LocalDate loadFrom = CalendarDocumentBuilder.loadFrom(req.view(), req.anchor());
        LocalDate loadTo = CalendarDocumentBuilder.loadTo(req.view(), req.anchor());
        return new CalendarDocumentBuilder(req, service.getEmployees(), service.getPositions(),
                service.assignMap(loadFrom, loadTo), LocalDateTime.now()).build();
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("anchor must be an ISO date, e.g. 2026-06-01");
        }
    }

    /** "kitchen-2026-07-27-week.pdf", or "schedule-…" once a batch spans several titles. */
    private static String filename(ExportDocument doc) {
        String base = doc.sections().size() == 1 ? slug(doc.sections().get(0).title()) : "schedule";
        String day = doc.sections().get(0).days().isEmpty()
                ? "export" : doc.sections().get(0).days().get(0).date();
        return base + "-" + day + "-" + doc.meta().view() + ".pdf";
    }

    private static String slug(String title) {
        String s = java.text.Normalizer.normalize(title == null ? "" : title, java.text.Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return s.isEmpty() ? "schedule" : s;
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(message))
                .build();
    }
}
