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
 * 3. For each node, compute suspiciousness as max of all edges that cover it
 *
 * Usage:
 *   java NodeRankingRecovery <edgeRanking.csv> <edges.csv> <nodeSpectra.csv> <output.csv>
 */
public class NodeRankingRecovery {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java NodeRankingRecovery <edgeRanking.csv> <edges.csv> <nodeSpectra.csv> <output.csv>");
            System.exit(1);
        }

        String edgeRankingFile = args[0];
        String edgesFile = args[1];
        String nodeSpectraFile = args[2];
        String outputFile = args[3];

        System.out.println("[Recovery] Edge ranking: " + edgeRankingFile);
        System.out.println("[Recovery] Edges file: " + edgesFile);
        System.out.println("[Recovery] Node spectra: " + nodeSpectraFile);
        System.out.println("[Recovery] Output: " + outputFile);

        // Step 1: Read edge ranking
        Map<String, Double> edgeRanking = readEdgeRanking(edgeRankingFile);
        System.out.println("[Recovery] Loaded " + edgeRanking.size() + " edge rankings");

        // Step 2: Read edge annotations (edge -> removed nodes)
        Map<String, EdgeInfo> edgeAnnotations = readEdgeAnnotations(edgesFile);
        System.out.println("[Recovery] Loaded " + edgeAnnotations.size() + " edge annotations");

        // Step 3: Read node spectra
        List<String> allNodes = readNodeSpectra(nodeSpectraFile);
        System.out.println("[Recovery] Loaded " + allNodes.size() + " nodes");

        // Step 4: Recover node ranking
        Map<String, Double> nodeRanking = recoverNodeRanking(edgeRanking, edgeAnnotations, allNodes);
        System.out.println("[Recovery] Recovered ranking for " + nodeRanking.size() + " nodes");

        // Step 5: Write output
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

        EdgeInfo(String fromNode, String toNode, List<String> removedNodes) {
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.removedNodes = removedNodes;
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
                    annotations.put(edgeName, new EdgeInfo(fromNode, toNode, removedNodes));
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
     * Recover node ranking from edge ranking.
     *
     * Algorithm:
     * 1. For each edge, the to_node gets the edge's suspiciousness (as incoming edge)
     * 2. Removed nodes along the edge also get the edge's suspiciousness
     * 3. from_node gets its suspiciousness from edges where it is the to_node (not from outgoing edges)
     * 4. Each node's final suspiciousness = max of all edges covering it
     *
     * Note: We don't propagate edge suspiciousness to from_node because:
     * - from_node's coverage is determined by edges where it is the destination
     * - Using from_node would incorrectly elevate suspiciousness of branching nodes
     */
    private static Map<String, Double> recoverNodeRanking(
            Map<String, Double> edgeRanking,
            Map<String, EdgeInfo> edgeAnnotations,
            List<String> allNodes) {

        // Initialize all nodes with 0 suspiciousness
        Map<String, Double> nodeRanking = new LinkedHashMap<>();
        for (String node : allNodes) {
            nodeRanking.put(node, 0.0);
        }

        // For each edge, propagate its suspiciousness to covered nodes
        for (Map.Entry<String, Double> entry : edgeRanking.entrySet()) {
            String edgeName = entry.getKey();
            double suspiciousness = entry.getValue();

            EdgeInfo edgeInfo = edgeAnnotations.get(edgeName);
            if (edgeInfo != null) {
                // NOTE: Do NOT update from_node - it gets its suspiciousness from
                // edges where it is the destination (to_node), not from outgoing edges.
                // This prevents branching nodes from inheriting high suspiciousness
                // from their high-suspiciousness branches.

                // Update to node (skip BLOCK virtual nodes)
                if (!edgeInfo.toNode.contains(":BLOCK")) {
                    updateNodeSuspiciousness(nodeRanking, edgeInfo.toNode, suspiciousness);
                }
                // Update removed nodes
                for (String removedNode : edgeInfo.removedNodes) {
                    updateNodeSuspiciousness(nodeRanking, removedNode, suspiciousness);
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
