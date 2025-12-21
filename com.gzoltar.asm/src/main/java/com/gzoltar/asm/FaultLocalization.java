/**
 * Copyright (C) 2020 GZoltar contributors.
 *
 * This file is part of GZoltar.
 */
package com.gzoltar.asm;

import java.io.*;
import java.util.*;

/**
 * Fault localization using Ochiai formula.
 *
 * Usage: java FaultLocalization <spectra.csv> <matrix.txt> <outputDir>
 *
 * Input:
 *   - spectra.csv: list of program elements (one per line, with header)
 *   - matrix.txt: coverage matrix (space-separated 0/1, last column is +/- for test result)
 *
 * Output:
 *   - ochiai.ranking.csv: sorted ranking by suspiciousness
 */
public class FaultLocalization {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java FaultLocalization <spectra.csv> <matrix.txt> <outputDir>");
            System.exit(1);
        }

        String spectraFile = args[0];
        String matrixFile = args[1];
        String outputDir = args[2];

        // Read spectra (element names)
        List<String> elements = readSpectra(spectraFile);
        System.out.println("[FL] Loaded " + elements.size() + " elements");

        // Read matrix
        MatrixData matrix = readMatrix(matrixFile);
        System.out.println("[FL] Loaded " + matrix.coverage.size() + " tests (" +
                          matrix.failedCount + " failed, " + matrix.passedCount + " passed)");

        // Calculate Ochiai for each element
        Map<String, Double> ranking = calculateOchiai(elements, matrix);

        // Sort by suspiciousness (descending)
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(ranking.entrySet());
        sorted.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        // Write ranking
        File outputFile = new File(outputDir, "ochiai.ranking.csv");
        try (PrintWriter pw = new PrintWriter(outputFile)) {
            pw.println("name;suspiciousness_value");
            for (Map.Entry<String, Double> entry : sorted) {
                pw.println(entry.getKey() + ";" + entry.getValue());
            }
        }
        System.out.println("[FL] Ranking saved to: " + outputFile.getAbsolutePath());

        // Print top 10
        System.out.println();
        System.out.println("[FL] Top 10 suspicious elements:");
        int count = 0;
        for (Map.Entry<String, Double> entry : sorted) {
            if (count++ >= 10) break;
            System.out.printf("  %d. %s (%.4f)%n", count, entry.getKey(), entry.getValue());
        }
    }

    private static List<String> readSpectra(String filename) throws IOException {
        List<String> elements = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) {
                    first = false;
                    continue; // Skip header
                }
                if (!line.trim().isEmpty()) {
                    elements.add(line.trim());
                }
            }
        }
        return elements;
    }

    static class MatrixData {
        List<boolean[]> coverage = new ArrayList<>();
        List<Boolean> failed = new ArrayList<>(); // true = failed
        int failedCount = 0;
        int passedCount = 0;
    }

    private static MatrixData readMatrix(String filename) throws IOException {
        MatrixData data = new MatrixData();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                boolean[] row = new boolean[parts.length - 1];
                for (int i = 0; i < parts.length - 1; i++) {
                    row[i] = parts[i].equals("1");
                }
                data.coverage.add(row);

                // + means failed, - means passed
                boolean isFailed = parts[parts.length - 1].equals("+");
                data.failed.add(isFailed);
                if (isFailed) {
                    data.failedCount++;
                } else {
                    data.passedCount++;
                }
            }
        }
        return data;
    }

    private static Map<String, Double> calculateOchiai(List<String> elements, MatrixData matrix) {
        Map<String, Double> ranking = new LinkedHashMap<>();

        int totalFailed = matrix.failedCount;

        for (int e = 0; e < elements.size(); e++) {
            int ef = 0; // failed tests that cover this element
            int ep = 0; // passed tests that cover this element
            int nf = 0; // failed tests that don't cover this element

            for (int t = 0; t < matrix.coverage.size(); t++) {
                boolean[] row = matrix.coverage.get(t);
                boolean covered = e < row.length && row[e];
                boolean failed = matrix.failed.get(t);

                if (covered && failed) ef++;
                else if (covered && !failed) ep++;
                else if (!covered && failed) nf++;
            }

            // Ochiai formula: ef / sqrt((ef + nf) * (ef + ep))
            double ochiai = 0.0;
            if (ef > 0) {
                double denom = Math.sqrt((double)(ef + nf) * (ef + ep));
                if (denom > 0) {
                    ochiai = ef / denom;
                }
            }

            ranking.put(elements.get(e), ochiai);
        }

        return ranking;
    }
}
