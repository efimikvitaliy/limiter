package com.v.limiter;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputFilesComparisonTest {
    @Test
    void bothFilesContainTheSameLinesRegardlessOfOrder() throws IOException {
        String firstPath = "/Users/vitali/input/output1.txt";
        String secondPath = "/Users/vitali/input/output.txt";
        Assumptions.assumeTrue(firstPath != null && secondPath != null,
                "Set COMPARE_FILE_A and COMPARE_FILE_B to compare the files");

        List<String> firstLines = Files.readAllLines(Path.of(firstPath), StandardCharsets.UTF_8);
        List<String> secondLines = Files.readAllLines(Path.of(secondPath), StandardCharsets.UTF_8);

        List<String> onlyInFirst = unmatchedLines(firstLines, secondLines);
        List<String> onlyInSecond = unmatchedLines(secondLines, firstLines);
        String differences = "Lines only in " + firstPath + ":\n" + String.join("\n", onlyInFirst)
                + "\nLines only in " + secondPath + ":\n" + String.join("\n", onlyInSecond);

        assertTrue(onlyInFirst.isEmpty() && onlyInSecond.isEmpty(), differences);
    }

    private List<String> unmatchedLines(List<String> source, List<String> other) {
        Map<String, Integer> available = new HashMap<>();
        for (String line : other) {
            available.merge(line, 1, Integer::sum);
        }

        List<String> unmatched = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            String line = source.get(index);
            int count = available.getOrDefault(line, 0);
            if (count == 0) {
                unmatched.add("  line " + (index + 1) + ": " + line);
            } else if (count == 1) {
                available.remove(line);
            } else {
                available.put(line, count - 1);
            }
        }
        return unmatched;
    }
}
