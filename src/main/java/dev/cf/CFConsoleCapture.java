package dev.cf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class CFConsoleCapture {
    private static volatile Path logFile;
    private CFConsoleCapture() {}

    static Path logFile() { return logFile; }

    static synchronized void install() {
        if (logFile != null) return;
        try {
            Files.createDirectories(Path.of("diagnostics", "server-logs"));
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC).format(Instant.now());
            Path path = Path.of("diagnostics", "server-logs", "server-full-" + stamp + ".log");
            OutputStream disk = Files.newOutputStream(path);
            PrintStream original = System.out;
            OutputStream tee = new OutputStream() {
                @Override public void write(int b) throws IOException {
                    original.write(b);
                    disk.write(b);
                }
                @Override public void write(byte[] b, int off, int length) throws IOException {
                    original.write(b, off, length);
                    disk.write(b, off, length);
                }
                @Override public void flush() throws IOException {
                    original.flush();
                    disk.flush();
                }
            };
            PrintStream both = new PrintStream(tee, true, StandardCharsets.UTF_8);
            System.setOut(both);
            System.setErr(both);
            logFile = path;
            Runtime.getRuntime().addShutdownHook(new Thread(both::flush));
            System.out.println("[CF FULL LOG] " + path.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot enable full console logging", e);
        }
    }
}
