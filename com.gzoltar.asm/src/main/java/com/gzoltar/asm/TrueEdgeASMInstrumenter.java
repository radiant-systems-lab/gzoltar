/**
 * Copyright (C) 2020 GZoltar contributors.
 *
 * This file is part of GZoltar.
 */
package com.gzoltar.asm;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Main entry point for TRUE edge-based ASM instrumentation.
 * This inserts actual edge basic blocks: p -> [edge_block] -> q
 *
 * Usage: java TrueEdgeASMInstrumenter <inputDir> <outputDir> [--includes=pattern]
 */
public class TrueEdgeASMInstrumenter {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java TrueEdgeASMInstrumenter <inputDir> <outputDir> [--includes=pattern]");
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

        System.out.println("[TrueEdge-ASM] Starting TRUE edge-based instrumentation");
        System.out.println("[TrueEdge-ASM] Input: " + inputDir.getAbsolutePath());
        System.out.println("[TrueEdge-ASM] Output: " + outputDir.getAbsolutePath());
        System.out.println("[TrueEdge-ASM] Include pattern: " + includePattern);
        System.out.println();

        TrueEdgeInstrumentor instrumentor = new TrueEdgeInstrumentor();
        Pattern pattern = Pattern.compile(includePattern);

        // Find all class files
        List<File> classFiles = findClassFiles(inputDir);
        List<File> matchingFiles = new ArrayList<>();

        System.out.println("[TrueEdge-ASM] Instrumenting classes...");

        int instrumentedCount = 0;
        for (File classFile : classFiles) {
            String className = getClassName(inputDir, classFile);
            byte[] classBytes = Files.readAllBytes(classFile.toPath());

            File outputFile = new File(outputDir, getRelativePath(inputDir, classFile));
            outputFile.getParentFile().mkdirs();

            if (pattern.matcher(className).matches()) {
                byte[] instrumented = instrumentor.instrument(classBytes);
                Files.write(outputFile.toPath(), instrumented);
                instrumentedCount++;
                System.out.println("[TrueEdge-ASM] Instrumented: " + className);
            } else {
                Files.copy(classFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Save annotations
        System.out.println();
        System.out.println("[TrueEdge-ASM] Saving edge annotations...");

        // Save edges.csv
        File edgesFile = new File(outputDir, "edges.csv");
        try (PrintWriter pw = new PrintWriter(edgesFile)) {
            pw.println("probe_id;method;from_node;to_node;removed_nodes");
            for (TrueEdgeInstrumentor.EdgeRecord edge : instrumentor.getProbedEdges()) {
                pw.println(edge.probeId + ";" +
                    edge.methodKey + ";" +
                    edge.fromNode + ";" +
                    edge.toNode + ";" +
                    String.join("|", edge.removedNodes));
            }
        }
        System.out.println("[TrueEdge-ASM] Edges saved to: " + edgesFile.getAbsolutePath());

        // Save spectra.csv
        File spectraFile = new File(outputDir, "spectra.csv");
        try (PrintWriter pw = new PrintWriter(spectraFile)) {
            pw.println("name");
            for (TrueEdgeInstrumentor.EdgeRecord edge : instrumentor.getProbedEdges()) {
                String edgeName = edge.fromNode + "->" + edge.toNode;
                pw.println(edgeName);
            }
        }

        // Save node_spectra.csv
        File nodeSpectraFile = new File(outputDir, "node_spectra.csv");
        try (PrintWriter pw = new PrintWriter(nodeSpectraFile)) {
            pw.println("name");
            for (String node : instrumentor.getAllNodes()) {
                pw.println(node);
            }
        }

        // Save probe count
        File probeCountFile = new File(outputDir, "probe_count.txt");
        Files.write(probeCountFile.toPath(), String.valueOf(instrumentor.getProbeCount()).getBytes());

        // Print summary
        System.out.println();
        System.out.println("[TrueEdge-ASM] ============ Summary ============");
        System.out.println("[TrueEdge-ASM] Classes instrumented: " + instrumentedCount);
        System.out.println("[TrueEdge-ASM] Total edge probes: " + instrumentor.getProbeCount());
        System.out.println("[TrueEdge-ASM] Total nodes: " + instrumentor.getAllNodes().size());
        System.out.println("[TrueEdge-ASM] =====================================");

        System.out.println();
        System.out.println("[TrueEdge-ASM] Probed edges:");
        for (TrueEdgeInstrumentor.EdgeRecord edge : instrumentor.getProbedEdges()) {
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
