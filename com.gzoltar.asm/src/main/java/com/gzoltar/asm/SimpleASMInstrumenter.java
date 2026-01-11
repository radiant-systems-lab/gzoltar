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
import java.util.regex.Pattern;

/**
 * Simple ASM-based instrumenter for line-level coverage.
 * This provides Basic Block-like coverage using ASM for offline instrumentation.
 *
 * Usage:
 *   java -cp gzoltar-asm.jar com.gzoltar.asm.SimpleASMInstrumenter \
 *       <inputDir> <outputDir> [--includes=pattern]
 */
public class SimpleASMInstrumenter {

    private final File inputDir;
    private final File outputDir;
    private final Pattern includePattern;
    private final SimpleNodeInstrumentor instrumentor;

    private int classesProcessed = 0;
    private int classesInstrumented = 0;

    public SimpleASMInstrumenter(File inputDir, File outputDir, String includes) {
        this.inputDir = inputDir;
        this.outputDir = outputDir;
        this.includePattern = includes != null ? globToRegex(includes) : null;
        this.instrumentor = new SimpleNodeInstrumentor();
    }

    public void run() throws IOException {
        System.out.println("[ASM] Starting instrumentation");
        System.out.println("[ASM] Input: " + inputDir.getAbsolutePath());
        System.out.println("[ASM] Output: " + outputDir.getAbsolutePath());

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Phase 1: Analyze all classes
        System.out.println("\n[ASM] Phase 1: Analyzing classes...");
        analyzeClasses(inputDir);

        // Phase 2: Instrument all classes
        System.out.println("\n[ASM] Phase 2: Instrumenting classes...");
        instrumentClasses(inputDir, outputDir);

        // Phase 3: Save spectra
        System.out.println("\n[ASM] Phase 3: Saving spectra...");
        saveSpectra();

        // Summary
        System.out.println("\n[ASM] ============ Summary ============");
        System.out.println("[ASM] Classes processed: " + classesProcessed);
        System.out.println("[ASM] Classes instrumented: " + classesInstrumented);
        System.out.println("[ASM] Total probes: " + instrumentor.getProbeCount());
        System.out.println("[ASM] =====================================");
    }

    private void analyzeClasses(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                analyzeClasses(file);
            } else if (file.getName().endsWith(".class")) {
                String className = getClassName(file);
                if (shouldProcess(className)) {
                    byte[] classBytes = Files.readAllBytes(file.toPath());
                    instrumentor.analyzeClass(classBytes);
                    System.out.println("[ASM] Analyzed: " + className);
                }
            }
        }
    }

    private void instrumentClasses(File srcDir, File destDir) throws IOException {
        File[] files = srcDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                File subDestDir = new File(destDir, file.getName());
                subDestDir.mkdirs();
                instrumentClasses(file, subDestDir);
            } else if (file.getName().endsWith(".class")) {
                classesProcessed++;
                String className = getClassName(file);
                File destFile = new File(destDir, file.getName());

                if (shouldProcess(className)) {
                    byte[] classBytes = Files.readAllBytes(file.toPath());
                    byte[] instrumentedBytes = instrumentor.instrument(classBytes);
                    Files.write(destFile.toPath(), instrumentedBytes);
                    classesInstrumented++;
                    System.out.println("[ASM] Instrumented: " + className);
                } else {
                    Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                File destFile = new File(destDir, file.getName());
                Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void saveSpectra() throws IOException {
        // Save spectra.csv
        File spectraFile = new File(outputDir, "spectra.csv");
        try (PrintWriter pw = new PrintWriter(spectraFile)) {
            pw.println("name");
            for (String node : instrumentor.getSpectra()) {
                pw.println(node);
            }
        }
        System.out.println("[ASM] Spectra saved to: " + spectraFile.getAbsolutePath());

        // Save probe count
        File probeCountFile = new File(outputDir, "probe_count.txt");
        try (PrintWriter pw = new PrintWriter(probeCountFile)) {
            pw.println(instrumentor.getProbeCount());
        }
    }

    private String getClassName(File file) {
        String path = file.getAbsolutePath();
        String basePath = inputDir.getAbsolutePath();
        String relativePath = path.substring(basePath.length() + 1);
        return relativePath.replace(File.separatorChar, '.').replace(".class", "");
    }

    private boolean shouldProcess(String className) {
        if (includePattern != null) {
            return includePattern.matcher(className).matches();
        }
        return true;
    }

    private Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*': regex.append(".*"); break;
                case '?': regex.append("."); break;
                case '.': regex.append("\\."); break;
                default: regex.append(c);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    public SimpleNodeInstrumentor getInstrumentor() {
        return instrumentor;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java -cp gzoltar-asm.jar com.gzoltar.asm.SimpleASMInstrumenter <inputDir> <outputDir> [--includes=pattern]");
            System.exit(1);
        }

        File inputDir = new File(args[0]);
        File outputDir = new File(args[1]);
        String includes = null;

        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--includes=")) {
                includes = args[i].substring("--includes=".length());
            }
        }

        SimpleASMInstrumenter instrumenter = new SimpleASMInstrumenter(inputDir, outputDir, includes);
        instrumenter.run();
    }
}
