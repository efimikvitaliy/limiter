package com.v.limiter.output;

import com.v.limiter.model.FundAcceptanceResult;
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
public class FundAcceptanceFileOutput implements FundAcceptanceOutput {
    private final Path outputPath;
    private final BufferedWriter writer;

    public FundAcceptanceFileOutput(@Value("${output.file.path}") String outputFilePath) {
        this.outputPath = Path.of(outputFilePath);
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(
                    outputPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open fund acceptance output: " + outputPath, e);
        }
    }

    @Override
    public synchronized void writeOutput(FundAcceptanceResult result) {
        try {
            writer.write("{\"id\":\"" + result.id()
                    + "\",\"customer_id\":\"" + result.customerId()
                    + "\",\"accepted\":" + result.accepted() + "}");
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write fund acceptance output to " + outputPath, e);
        }
    }

    @PreDestroy
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to close fund acceptance output: " + outputPath, e);
        }
    }
}
