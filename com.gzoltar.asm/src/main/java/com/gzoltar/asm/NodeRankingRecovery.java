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

/**
 * Recovers node-level ranking from edge-level ranking using edge annotations.
 *
 * The recovery process:
 * 1. Read edge ranking (edge -> suspiciousness)
 * 2. Read edge annotations (edge -> removed nodes)
 * 3. For to_node: use edge suspiciousness directly
 * 4. For removed_node: compute union coverage from all containing edges, recalculate Ochiai
 *
 * Usage:
 *   java NodeRankingRecovery edgeRanking.csv edges.csv nodeSpectra.csv output.csv [spectra.csv matrix.txt]
 *
 * If spectra.csv and matrix.txt are provided, removed nodes get accurate union-based Ochiai.
 * Otherwise, removed nodes use max edge suspiciousness (less accurate).
 */
public class NodeRankingRecovery {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java NodeRankingRecovery <edgeRanking.csv> <edges.csv> <nodeSpectra.csv> <output.csv> [spectra.csv matrix.txt]");
            System.exit(1);
        }

        String edgeRankingFile = args[0];
        String edgesFile = args[1];
        String nodeSpectraFile = args[2];
        String outputFile = args[3];
        String spectraFile = args.length > 4 ? args[4] : null;
        String matrixFile = args.length > 5 ? args[5] : null;

        System.out.println("[Recovery] Edge ranking: " + edgeRankingFile);
        System.out.println("[Recovery] Edges file: " + edgesFile);
        System.out.println("[Recovery] Node spectra: " + nodeSpectraFile);
        System.out.println("[Recovery] Output: " + outputFile);
        if (spectraFile != null) {
            System.out.println("[Recovery] Spectra: " + spectraFile);
            System.out.println("[Recovery] Matrix: " + matrixFile);
        }

        // Step 1: Read edge ranking
        Map<String, Double> edgeRanking = readEdgeRanking(edgeRankingFile);
        System.out.println("[Recovery] Loaded " + edgeRanking.size() + " edge rankings");

        // Step 2: Read edge annotations (edge -> removed nodes)
        Map<String, EdgeInfo> edgeAnnotations = readEdgeAnnotations(edgesFile);
        System.out.println("[Recovery] Loaded " + edgeAnnotations.size() + " edge annotations");

        // Step 3: Read node spectra
        List<String> allNodes = readNodeSpectra(nodeSpectraFile);
        System.out.println("[Recovery] Loaded " + allNodes.size() + " nodes");

        // Step 4: Read edge spectra and matrix if provided (for accurate removed node calculation)
        List<String> edgeSpectra = null;
        List<boolean[]> coverageMatrix = null;
        List<Boolean> testResults = null;
        int totalFailed = 0;
        int totalPassed = 0;

        if (spectraFile != null && matrixFile != null) {
            edgeSpectra = readEdgeSpectra(spectraFile);
            System.out.println("[Recovery] Loaded " + edgeSpectra.size() + " edge spectra entries");

            MatrixData matrixData = readMatrix(matrixFile);
            coverageMatrix = matrixData.coverage;
            testResults = matrixData.results;
            for (boolean failed : testResults) {
                if (failed) totalFailed++;
                else totalPassed++;
            }
            System.out.println("[Recovery] Loaded matrix: " + coverageMatrix.size() + " tests, " +
                             totalFailed + " failed, " + totalPassed + " passed");
        }

        // Step 5: Recover node ranking
        Map<String, Double> nodeRanking = recoverNodeRanking(
            edgeRanking, edgeAnnotations, allNodes,
            edgeSpectra, coverageMatrix, testResults, totalFailed, totalPassed);
        System.out.println("[Recovery] Recovered ranking for " + nodeRanking.size() + " nodes");

        // Step 6: Write output
        writeNodeRanking(outputFile, nodeRanking);
        System.out.println("[Recovery] Saved to: " + outputFile);
    }

    /**
     * Read edge ranking from CSV file.
     * Format: name;suspiciousness_value
     */
    private static Map<String, Double> readEdgeRanking(String filename) throws IOException {
        Map<String, Double> ranking = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // Skip header
                }
                String[] parts = line.split(";");
                if (parts.length >= 2) {
                    String edgeName = parts[0].trim();
                    double suspiciousness = Double.parseDouble(parts[1].trim());
                    ranking.put(edgeName, suspiciousness);
                }
            }
        }
        return ranking;
    }

    /**
     * Edge information from edges.csv
     */
    static class EdgeInfo {
        String fromNode;
        String toNode;
        List<String> removedNodes;
        int probeId;  // Added for matrix lookup

        EdgeInfo(String fromNode, String toNode, List<String> removedNodes, int probeId) {
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.removedNodes = removedNodes;
            this.probeId = probeId;
        }
    }

    /**
     * Read edge annotations from edges.csv.
     * Format: probe_id;method;from_node;to_node;removed_nodes (semicolon-delimited)
     */
    private static Map<String, EdgeInfo> readEdgeAnnotations(String filename) throws IOException {
        Map<String, EdgeInfo> annotations = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // Skip header
                }
                String[] parts = line.split(";", 5);
                if (parts.length >= 4) {
                    int probeId = Integer.parseInt(parts[0].trim());
                    String fromNode = parts[2].trim();
                    String toNode = parts[3].trim();
                    List<String> removedNodes = new ArrayList<>();
                    if (parts.length >= 5 && !parts[4].trim().isEmpty()) {
                        for (String node : parts[4].split("\\|")) {
                            if (!node.trim().isEmpty()) {
                                removedNodes.add(node.trim());
                            }
                        }
                    }

                    // Edge name format: fromNode->toNode
                    String edgeName = fromNode + "->" + toNode;
                    annotations.put(edgeName, new EdgeInfo(fromNode, toNode, removedNodes, probeId));
                }
            }
        }
        return annotations;
    }

    /**
     * Read node spectra from CSV.
     * Format: name
     */
    private static List<String> readNodeSpectra(String filename) throws IOException {
        List<String> nodes = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // Skip header
                }
                if (!line.trim().isEmpty()) {
                    nodes.add(line.trim());
                }
            }
        }
        return nodes;
    }

    /**
     * Read edge spectra from CSV.
     * Format: name (one edge name per line, with header)
     */
    private static List<String> readEdgeSpectra(String filename) throws IOException {
        List<String> edges = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // Skip header
                }
                if (!line.trim().isEmpty()) {
                    edges.add(line.trim());
                }
            }
        }
        return edges;
    }

    static class MatrixData {
        List<boolean[]> coverage;
        List<Boolean> results;
        MatrixData(List<boolean[]> coverage, List<Boolean> results) {
            this.coverage = coverage;
            this.results = results;
        }
    }

    /**
     * Read coverage matrix.
     * Format: space-separated 0/1 values, last column is +/- for test result
     */
    private static MatrixData readMatrix(String filename) throws IOException {
        List<boolean[]> coverage = new ArrayList<>();
        List<Boolean> results = new ArrayList<>();
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
                coverage.add(row);
                results.add(parts[parts.length - 1].equals("+"));
            }
        }
        return new MatrixData(coverage, results);
    }

    /**
     * Recover node ranking from edge ranking.
     *
     * For to_node: use edge suspiciousness directly (max of all incoming edges)
     * For removed_node: compute union coverage from all containing edges, recalculate Ochiai
     */
    private static Map<String, Double> recoverNodeRanking(
            Map<String, Double> edgeRanking,
            Map<String, EdgeInfo> edgeAnnotations,
            List<String> allNodes,
            List<String> edgeSpectra,
            List<boolean[]> coverageMatrix,
            List<Boolean> testResults,
            int totalFailed,
            int totalPassed) {

        // Build edge name to spectra index map
        Map<String, Integer> edgeToIndex = new HashMap<>();
        if (edgeSpectra != null) {
            for (int i = 0; i < edgeSpectra.size(); i++) {
                edgeToIndex.put(edgeSpectra.get(i), i);
            }
        }

        // Build removed_node -> list of edges containing it
        Map<String, List<String>> removedNodeToEdges = new HashMap<>();
        for (Map.Entry<String, EdgeInfo> entry : edgeAnnotations.entrySet()) {
            String edgeName = entry.getKey();
            for (String removedNode : entry.getValue().removedNodes) {
                removedNodeToEdges.computeIfAbsent(removedNode, k -> new ArrayList<>()).add(edgeName);
            }
        }

        // Initialize all nodes with 0 suspiciousness
        Map<String, Double> nodeRanking = new LinkedHashMap<>();
        for (String node : allNodes) {
            nodeRanking.put(node, 0.0);
        }

        // For each edge, propagate its suspiciousness to to_node
        for (Map.Entry<String, Double> entry : edgeRanking.entrySet()) {
            String edgeName = entry.getKey();
            double suspiciousness = entry.getValue();

            EdgeInfo edgeInfo = edgeAnnotations.get(edgeName);
            if (edgeInfo != null) {
                // Update to_node (skip BLOCK virtual nodes)
                if (!edgeInfo.toNode.contains(":BLOCK")) {
                    updateNodeSuspiciousness(nodeRanking, edgeInfo.toNode, suspiciousness);
                }
            }
        }

        // For removed nodes: compute union coverage and recalculate Ochiai
        if (coverageMatrix != null && edgeSpectra != null) {
            for (Map.Entry<String, List<String>> entry : removedNodeToEdges.entrySet()) {
                String removedNode = entry.getKey();
                List<String> containingEdges = entry.getValue();

                // Compute union coverage: a test covers the node if ANY of its edges is covered
                int ef = 0, ep = 0, nf = 0;
                for (int t = 0; t < coverageMatrix.size(); t++) {
                    boolean covered = false;
                    for (String edgeName : containingEdges) {
                        Integer idx = edgeToIndex.get(edgeName);
                        if (idx != null && idx < coverageMatrix.get(t).length && coverageMatrix.get(t)[idx]) {
                            covered = true;
                            break;
                        }
                    }
                    boolean failed = testResults.get(t);
                    if (covered && failed) ef++;
                    else if (covered && !failed) ep++;
                    else if (!covered && failed) nf++;
                }

                // Calculate Ochiai
                double ochiai = 0.0;
                if (ef > 0) {
                    double denom = Math.sqrt((double)(ef + nf) * (ef + ep));
                    if (denom > 0) {
                        ochiai = ef / denom;
                    }
                }
                nodeRanking.put(removedNode, ochiai);
            }
        } else {
            // Fallback: use max edge suspiciousness for removed nodes
            for (Map.Entry<String, Double> entry : edgeRanking.entrySet()) {
                String edgeName = entry.getKey();
                double suspiciousness = entry.getValue();

                EdgeInfo edgeInfo = edgeAnnotations.get(edgeName);
                if (edgeInfo != null) {
                    for (String removedNode : edgeInfo.removedNodes) {
                        updateNodeSuspiciousness(nodeRanking, removedNode, suspiciousness);
                    }
                }
            }
        }

        // Sort by suspiciousness (descending), then by name
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(nodeRanking.entrySet());
        sortedEntries.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        Map<String, Double> sortedRanking = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : sortedEntries) {
            sortedRanking.put(entry.getKey(), entry.getValue());
        }

        return sortedRanking;
    }

    private static void updateNodeSuspiciousness(Map<String, Double> nodeRanking, String node, double suspiciousness) {
        Double current = nodeRanking.get(node);
        if (current == null || suspiciousness > current) {
            nodeRanking.put(node, suspiciousness);
        }
    }

    /**
     * Write node ranking to CSV.
     * Format: name;suspiciousness_value
     */
    private static void writeNodeRanking(String filename, Map<String, Double> ranking) throws IOException {
        try (PrintWriter pw = new PrintWriter(filename)) {
            pw.println("name;suspiciousness_value");
            for (Map.Entry<String, Double> entry : ranking.entrySet()) {
                pw.println(entry.getKey() + ";" + entry.getValue());
            }
        }
    }
}
