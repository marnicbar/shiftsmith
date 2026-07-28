package dev.shiftsmith.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shiftsmith.export.ExportDocument;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Renders a calendar PDF with <a href="https://typst.app">Typst</a>.
 *
 * <p>This is only the typesetting half: {@code dev.shiftsmith.export.CalendarDocumentBuilder}
 * decides what goes on the page. Here we drop that document's JSON — and the brand
 * mark the footer prints — next to {@code typst/calendar.typ} in a throwaway directory
 * and shell out to the {@code typst} binary. The template reads the JSON and draws it;
 * it composes no text of its own.
 *
 * <p>Typst runs sandboxed to that directory ({@code --root}), so the template cannot
 * reach outside it. The binary is invoked with a hard timeout and the scratch
 * directory is always deleted.
 */
@ApplicationScoped
public class PdfExportService {

    private static final Logger LOG = Logger.getLogger(PdfExportService.class);

    /** Template shipped on the classpath; copied into each render's scratch directory. */
    private static final String TEMPLATE_RESOURCE = "typst/calendar.typ";
    private static final String TEMPLATE_NAME = "calendar.typ";
    /**
     * The ShiftSmith mark the footer prints. Typst may only read files inside the
     * sandbox root, so it is staged alongside the template under its bare name; the
     * template refers to it as {@code image("logo.svg")}.
     */
    private static final String LOGO_RESOURCE = "typst/logo.svg";
    private static final String LOGO_NAME = "logo.svg";
    private static final String DATA_NAME = "data.json";
    private static final String OUTPUT_NAME = "out.pdf";

    /** A calendar page is tiny; anything larger is a bug or an attack. */
    private static final int MAX_DOC_BYTES = 4 * 1024 * 1024;

    @ConfigProperty(name = "shiftsmith.typst.bin", defaultValue = "typst")
    String typstBin;

    @ConfigProperty(name = "shiftsmith.typst.timeout-seconds", defaultValue = "30")
    int timeoutSeconds;

    @Inject
    ObjectMapper mapper;

    /** Thrown when the render fails; the message is safe to show the user. */
    public static class ExportException extends RuntimeException {
        public ExportException(String message) { super(message); }
        public ExportException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Compile {@code document} (the model described in {@code calendar.typ}) into PDF bytes.
     */
    public byte[] render(ExportDocument document) {
        if (document == null || document.sections() == null || document.sections().isEmpty()) {
            throw new ExportException("export document has no sections to render");
        }
        byte[] data;
        try {
            data = mapper.writeValueAsBytes(document);
        } catch (IOException e) {
            throw new ExportException("could not serialize the export document", e);
        }
        if (data.length > MAX_DOC_BYTES) {
            throw new ExportException("export document too large (" + data.length + " bytes)");
        }

        Path dir = null;
        try {
            dir = Files.createTempDirectory("shiftsmith-export-");
            Files.write(dir.resolve(DATA_NAME), data);
            Files.write(dir.resolve(TEMPLATE_NAME), resource(TEMPLATE_RESOURCE));
            Files.write(dir.resolve(LOGO_NAME), resource(LOGO_RESOURCE));
            return compile(dir);
        } catch (IOException e) {
            throw new ExportException("could not prepare the PDF render", e);
        } finally {
            deleteRecursively(dir);
        }
    }

    private byte[] compile(Path dir) throws IOException {
        Path out = dir.resolve(OUTPUT_NAME);
        Path log = dir.resolve("typst.log");
        ProcessBuilder pb = new ProcessBuilder(List.of(
                typstBin, "compile",
                // Sandbox: the template may only read files under the scratch directory.
                "--root", dir.toString(),
                "--format", "pdf",
                dir.resolve(TEMPLATE_NAME).toString(),
                out.toString()));
        pb.directory(dir.toFile());
        // Send diagnostics to a file rather than a pipe, so a chatty run can never fill
        // the buffer and deadlock us while we are waiting on the timeout.
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new ExportException(
                    "PDF export is unavailable: the 'typst' binary was not found (" + typstBin + ")", e);
        }
        boolean finished;
        try {
            finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new ExportException("PDF render interrupted", e);
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new ExportException("PDF render timed out after " + timeoutSeconds + "s");
        }
        if (proc.exitValue() != 0 || !Files.exists(out)) {
            LOG.errorf("typst compile failed (exit %d):%n%s", proc.exitValue(), readLog(log));
            throw new ExportException("PDF render failed");
        }
        return Files.readAllBytes(out);
    }

    private static String readLog(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "(no output)";
        } catch (IOException e) {
            return "(log unreadable: " + e.getMessage() + ")";
        }
    }

    /** Reads one of the render's classpath assets (template, brand mark). */
    private static byte[] resource(String name) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(name)) {
            if (in == null) throw new ExportException("missing template resource " + name);
            return in.readAllBytes();
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
            });
        } catch (IOException e) {
            LOG.warnf(e, "could not clean up export scratch directory %s", dir);
        }
    }
}
