package com.v.limiter.input;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class RequestsFileReader implements RequestsSource {
    private final BufferedReader reader;

    public RequestsFileReader(@Value("${input.file.path}") String filePath) {
        try {
            this.reader = Files.newBufferedReader(Path.of(filePath));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open requests file: " + filePath, e);
        }
    }

    @Override
    public synchronized String read() {
        try {
            return reader.readLine();
        } catch (IOException e) {
            log.error("Error reading from requests file", e);
            throw new IllegalStateException("Unable to read next request line", e);
        }
    }
}
