package org.texttechnologylab.uce.common.metrics;

import java.time.Instant;
import java.io.BufferedWriter;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class UCEProfileRecorder {
    public static final String ENABLED_PROPERTY = "uce.profile.enabled";
    public static final String SINK_PROPERTY = "uce.profile.sink";

    private static final Scope NOOP = new Scope("", "", "duration", 0L, false);
    private static BufferedWriter writer;
    private static String writerSink;

    private UCEProfileRecorder() {
    }

    public static Scope scope(String name) {
        return scope(name, "", "duration");
    }

    public static Scope scope(String name, String domain) {
        return scope(name, domain, "duration");
    }

    public static Scope scope(String name, String domain, String kind) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return NOOP;
        }
        return new Scope(
                Objects.requireNonNullElse(name, ""),
                Objects.requireNonNullElse(domain, ""),
                Objects.requireNonNullElse(kind, "duration"),
                System.nanoTime(),
                true
        );
    }

    public static void event(String name, String domain, String kind, String status, int attempt, String error) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return;
        }
        emit(
                Objects.requireNonNullElse(name, ""),
                Objects.requireNonNullElse(domain, ""),
                Objects.requireNonNullElse(kind, "event"),
                -1L,
                Objects.requireNonNullElse(status, ""),
                attempt,
                error
        );
    }

    public static final class Scope implements AutoCloseable {
        private final String name;
        private final String domain;
        private final String kind;
        private final long startedNanos;
        private final boolean enabled;
        private boolean closed;

        private Scope(String name, String domain, String kind, long startedNanos, boolean enabled) {
            this.name = name;
            this.domain = domain;
            this.kind = kind;
            this.startedNanos = startedNanos;
            this.enabled = enabled;
        }

        @Override
        public void close() {
            if (!enabled || closed) {
                return;
            }
            closed = true;
            long elapsedNanos = System.nanoTime() - startedNanos;
            emit(name, domain, kind, elapsedNanos, "completed", 0, null);
        }
    }

    private static synchronized void emit(String name, String domain, String kind, long elapsedNanos, String status, int attempt, String error) {
        String sink = System.getProperty(SINK_PROPERTY, "stdout");
        String line = jsonLine(name, domain, kind, elapsedNanos, status, attempt, error);
        if ("stdout".equalsIgnoreCase(sink)) {
            System.out.println(line);
            return;
        }
        if ("none".equalsIgnoreCase(sink) || "noop".equalsIgnoreCase(sink)) {
            return;
        }
        try {
            BufferedWriter output = writerFor(sink);
            output.write(line);
            output.newLine();
        } catch (IOException ex) {
            System.err.println("Could not write UCE profile event to " + sink + ": " + ex.getMessage());
            System.out.println(line);
        }
    }

    private static BufferedWriter writerFor(String sink) throws IOException {
        if (writer != null && sink.equals(writerSink)) {
            return writer;
        }
        closeWriter();
        String path = sink.startsWith("file:") ? sink.substring("file:".length()) : sink;
        Path output = Path.of(path);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writerSink = sink;
        Runtime.getRuntime().addShutdownHook(new Thread(UCEProfileRecorder::flushAndCloseWriter, "uce-profile-recorder-close"));
        return writer;
    }

    private static synchronized void flushAndCloseWriter() {
        try {
            closeWriter();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void closeWriter() throws IOException {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
            writerSink = null;
        }
    }

    private static String jsonLine(String name, String domain, String kind, long elapsedNanos, String status, int attempt, String error) {
        long durationMs = elapsedNanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return "{"
                + "\"time\":\"" + escape(Instant.now().toString()) + "\","
                + "\"name\":\"" + escape(name) + "\","
                + "\"domain\":\"" + escape(domain) + "\","
                + "\"kind\":\"" + escape(kind) + "\","
                + "\"status\":\"" + escape(status) + "\","
                + "\"attempt\":" + attempt + ","
                + "\"duration_ms\":" + durationMs + ","
                + "\"thread\":\"" + escape(Thread.currentThread().getName()) + "\","
                + "\"error\":" + (error == null ? "null" : "\"" + escape(error) + "\"")
                + "}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
