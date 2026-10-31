package com.v.limiter.input;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Service
public class DLQWriter implements DLQOutput {
    private final Path filePath;
    private final BufferedWriter writer;

    public DLQWriter(@Value("${dlq.file.path}") String filePath) {
        this.filePath = Path.of(filePath);
        try {
            Path parent = this.filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(
                    this.filePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open DLQ file: " + this.filePath, e);
        }
    }

    @Override
    public synchronized void write(String message) {
        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write request to DLQ: " + filePath, e);
        }
    }

    @PreDestroy
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to close DLQ file: " + filePath, e);
        }
    }
}
