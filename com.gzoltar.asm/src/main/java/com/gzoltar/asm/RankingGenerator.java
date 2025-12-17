/**
 * Copyright (C) 2020 GZoltar contributors.
 *
 * This file is part of GZoltar.
 *
 * GZoltar is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * GZoltar is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with GZoltar. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package com.gzoltar.asm;

import java.io.*;
import java.util.*;

/**
 * Generates fault localization ranking from coverage data.
 */
public class RankingGenerator {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java RankingGenerator <spectra.csv> <matrix.txt> <output_dir>");
            System.exit(1);
        }

        File spectraFile = new File(args[0]);
        File matrixFile = new File(args[1]);
        File outputDir = new File(args[2]);
        outputDir.mkdirs();

        // Read spectra
        List<String> spectra = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(spectraFile))) {
            String line = br.readLine(); // Skip header
            while ((line = br.readLine()) != null) {
                spectra.add(line.trim());
            }
        }

        // Read matrix
        List<boolean[]> coverageMatrix = new ArrayList<>();
        List<Boolean> testResults = new ArrayList<>();  // true = fail, false = pass

        try (BufferedReader br = new BufferedReader(new FileReader(matrixFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                boolean[] coverage = new boolean[parts.length - 1];
                for (int i = 0; i < parts.length - 1; i++) {
                    coverage[i] = parts[i].equals("1");
                }
                coverageMatrix.add(coverage);

                // + means fail, - means pass
                String result = parts[parts.length - 1];
                testResults.add(result.equals("+"));
            }
        }

        int numProbes = spectra.size();
        int numTests = coverageMatrix.size();

        // Count total failed and passed tests
        int totalFailed = 0;
        int totalPassed = 0;
        for (boolean failed : testResults) {
            if (failed) totalFailed++;
            else totalPassed++;
        }

        System.out.println("Spectra size: " + numProbes);
        System.out.println("Tests: " + numTests + " (failed=" + totalFailed + ", passed=" + totalPassed + ")");

        // Calculate suspiciousness for each probe
        List<ProbeScore> scores = new ArrayList<>();

        for (int p = 0; p < numProbes; p++) {
            int ef = 0;  // executed by failed tests
            int ep = 0;  // executed by passed tests
            int nf = 0;  // not executed by failed tests
            int np = 0;  // not executed by passed tests

            for (int t = 0; t < numTests; t++) {
                boolean covered = coverageMatrix.get(t)[p];
                boolean failed = testResults.get(t);

                if (covered && failed) ef++;
                else if (covered && !failed) ep++;
                else if (!covered && failed) nf++;
                else np++;
            }

            double ochiai = calculateOchiai(ef, ep, nf, np);
            double tarantula = calculateTarantula(ef, ep, nf, np, totalFailed, totalPassed);

            scores.add(new ProbeScore(spectra.get(p), ochiai, tarantula));
        }

        // Sort by Ochiai score descending
        scores.sort((a, b) -> Double.compare(b.ochiai, a.ochiai));

        // Write Ochiai ranking
        File ochiaiFile = new File(outputDir, "ochiai.ranking.csv");
        try (PrintWriter pw = new PrintWriter(ochiaiFile)) {
            pw.println("name;suspiciousness_value");
            for (ProbeScore score : scores) {
                pw.println(score.name + ";" + score.ochiai);
            }
        }

        // Sort by Tarantula score descending
        scores.sort((a, b) -> Double.compare(b.tarantula, a.tarantula));

        // Write Tarantula ranking
        File tarantulaFile = new File(outputDir, "tarantula.ranking.csv");
        try (PrintWriter pw = new PrintWriter(tarantulaFile)) {
            pw.println("name;suspiciousness_value");
            for (ProbeScore score : scores) {
                pw.println(score.name + ";" + score.tarantula);
            }
        }

        // Write statistics
        File statsFile = new File(outputDir, "statistics.csv");
        try (PrintWriter pw = new PrintWriter(statsFile)) {
            pw.println("formula,metric,value");
            pw.println("all,number_of_components," + numProbes);
            pw.println("all,number_of_test_cases," + numTests);
            pw.println("all,number_of_failing_test_cases," + totalFailed);
            pw.println("all,number_of_passing_test_cases," + totalPassed);
        }

        System.out.println("\nRanking files saved to: " + outputDir.getAbsolutePath());
        System.out.println("\n=== Top 5 Ochiai Ranking ===");
        scores.sort((a, b) -> Double.compare(b.ochiai, a.ochiai));
        for (int i = 0; i < Math.min(5, scores.size()); i++) {
            ProbeScore s = scores.get(i);
            System.out.printf("%d. %s (ochiai=%.4f)%n", i + 1, s.name, s.ochiai);
        }
    }

    private static double calculateOchiai(int ef, int ep, int nf, int np) {
        if (ef == 0) return 0.0;
        double denominator = Math.sqrt((ef + nf) * (ef + ep));
        if (denominator == 0) return 0.0;
        return ef / denominator;
    }

    private static double calculateTarantula(int ef, int ep, int nf, int np, int totalFailed, int totalPassed) {
        if (ef == 0 || totalFailed == 0) return 0.0;

        double failedRatio = (double) ef / totalFailed;
        double passedRatio = (totalPassed > 0) ? (double) ep / totalPassed : 0.0;

        double denominator = failedRatio + passedRatio;
        if (denominator == 0) return 0.0;

        return failedRatio / denominator;
    }

    static class ProbeScore {
        String name;
        double ochiai;
        double tarantula;

        ProbeScore(String name, double ochiai, double tarantula) {
            this.name = name;
            this.ochiai = ochiai;
            this.tarantula = tarantula;
        }
    }
}
