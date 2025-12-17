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
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Main entry point for PRS-based edge ASM instrumentation.
 *
 * Usage: java PRSEdgeASMInstrumenter <inputDir> <outputDir> [--includes=pattern]
 */
public class PRSEdgeASMInstrumenter {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java PRSEdgeASMInstrumenter <inputDir> <outputDir> [--includes=pattern]");
            System.exit(1);
        }

        File inputDir = new File(args[0]);
        File outputDir = new File(args[1]);

        String includePattern = ".*";
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--includes=")) {
                includePattern = args[i].substring("--includes=".length());
                includePattern = includePattern.replace(".", "\\.").replace("*", ".*");
            }
        }

        System.out.println("[PRS-ASM] Starting PRS edge-based instrumentation");
        System.out.println("[PRS-ASM] Input: " + inputDir.getAbsolutePath());
        System.out.println("[PRS-ASM] Output: " + outputDir.getAbsolutePath());
        System.out.println("[PRS-ASM] Include pattern: " + includePattern);
        System.out.println();

        PRSEdgeInstrumentor instrumentor = new PRSEdgeInstrumentor();
        Pattern pattern = Pattern.compile(includePattern);

        // Phase 1: Analyze all matching classes
        System.out.println("[PRS-ASM] Phase 1: Analyzing classes...");
        List<File> classFiles = findClassFiles(inputDir);
        List<File> matchingFiles = new ArrayList<>();

        for (File classFile : classFiles) {
            String className = getClassName(inputDir, classFile);
            if (pattern.matcher(className).matches()) {
                matchingFiles.add(classFile);
                byte[] classBytes = Files.readAllBytes(classFile.toPath());
                instrumentor.analyzeClass(classBytes);
                System.out.println("[PRS-ASM] Analyzed: " + className);
            }
        }

        System.out.println();
        System.out.println("[PRS-ASM] Phase 2: Instrumenting classes...");

        // Phase 2: Instrument all matching classes
        int instrumentedCount = 0;
        for (File classFile : matchingFiles) {
            String className = getClassName(inputDir, classFile);
            byte[] classBytes = Files.readAllBytes(classFile.toPath());
            byte[] instrumented = instrumentor.instrument(classBytes);

            File outputFile = new File(outputDir, getRelativePath(inputDir, classFile));
            outputFile.getParentFile().mkdirs();
            Files.write(outputFile.toPath(), instrumented);
            instrumentedCount++;
            System.out.println("[PRS-ASM] Instrumented: " + className);
        }

        // Copy non-matching classes
        for (File classFile : classFiles) {
            String className = getClassName(inputDir, classFile);
            if (!pattern.matcher(className).matches()) {
                File outputFile = new File(outputDir, getRelativePath(inputDir, classFile));
                outputFile.getParentFile().mkdirs();
                Files.copy(classFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Phase 3: Save annotations
        System.out.println();
        System.out.println("[PRS-ASM] Phase 3: Saving edge annotations...");

        // Save edges.csv (using semicolon as delimiter to avoid issues with commas in method signatures)
        File edgesFile = new File(outputDir, "edges.csv");
        try (PrintWriter pw = new PrintWriter(edgesFile)) {
            pw.println("probe_id;method;from_node;to_node;removed_nodes");
            for (PRSEdgeInstrumentor.EdgeRecord edge : instrumentor.getProbedEdges()) {
                pw.println(edge.probeId + ";" +
                    edge.methodKey + ";" +
                    edge.fromNode + ";" +
                    edge.toNode + ";" +
                    String.join("|", edge.removedNodes));
            }
        }
        System.out.println("[PRS-ASM] Edges saved to: " + edgesFile.getAbsolutePath());

        // Save spectra.csv (edge spectra)
        File spectraFile = new File(outputDir, "spectra.csv");
        try (PrintWriter pw = new PrintWriter(spectraFile)) {
            pw.println("name");
            for (PRSEdgeInstrumentor.EdgeRecord edge : instrumentor.getProbedEdges()) {
                String edgeName = edge.fromNode + "->" + edge.toNode;
                pw.println(edgeName);
            }
        }
        System.out.println("[PRS-ASM] Edge spectra saved to: " + spectraFile.getAbsolutePath());

        // Save node_spectra.csv
        File nodeSpectraFile = new File(outputDir, "node_spectra.csv");
        try (PrintWriter pw = new PrintWriter(nodeSpectraFile)) {
            pw.println("name");
            for (String node : instrumentor.getAllNodes()) {
                pw.println(node);
            }
        }
        System.out.println("[PRS-ASM] Node spectra saved to: " + nodeSpectraFile.getAbsolutePath());

        // Save probe count
        File probeCountFile = new File(outputDir, "probe_count.txt");
        Files.write(probeCountFile.toPath(), String.valueOf(instrumentor.getProbeCount()).getBytes());

        // Print summary
        System.out.println();
        System.out.println("[PRS-ASM] ============ Summary ============");
        System.out.println("[PRS-ASM] Classes instrumented: " + instrumentedCount);
        System.out.println("[PRS-ASM] Total edge probes: " + instrumentor.getProbeCount());
        System.out.println("[PRS-ASM] Total nodes: " + instrumentor.getAllNodes().size());
        System.out.println("[PRS-ASM] =====================================");

        // Print edges for debugging
        System.out.println();
        System.out.println("[PRS-ASM] Probed edges:");
        for (PRSEdgeInstrumentor.EdgeRecord edge : instrumentor.getProbedEdges()) {
            System.out.println("  " + edge);
        }
    }

    private static List<File> findClassFiles(File dir) {
        List<File> result = new ArrayList<>();
        findClassFilesRecursive(dir, result);
        return result;
    }

    private static void findClassFilesRecursive(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                findClassFilesRecursive(file, result);
            } else if (file.getName().endsWith(".class")) {
                result.add(file);
            }
        }
    }

    private static String getClassName(File baseDir, File classFile) {
        String relativePath = getRelativePath(baseDir, classFile);
        return relativePath.replace(File.separatorChar, '.')
                          .replace('/', '.')
                          .replaceAll("\\.class$", "");
    }

    private static String getRelativePath(File baseDir, File file) {
        return baseDir.toPath().relativize(file.toPath()).toString();
    }
}
