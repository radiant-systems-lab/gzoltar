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
package com.gzoltar.report.fl;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool to expand EDGE-based ranking to basic block level ranking.
 *
 * This class reads the edge-based ranking from EDGE mode and expands
 * each edge to all the basic blocks it traverses, assigning the edge's suspiciousness
 * to each block on that path.
 *
 * Supports two edge formats:
 * - New format: method:line#EDGE:fromLine->toLine (e.g., mypackage$App#mid(int,int,int):5#EDGE:ENTRY->5)
 * - Old format: method:fromLine->toLine (e.g., mypackage$App#mid(int,int,int):7->8)
 *
 * Usage:
 * <pre>
 * EdgeRankingExpander expander = new EdgeRankingExpander();
 * Map&lt;String, Double&gt; blockRanking = expander.expandEdgeRanking("path/to/ranking.csv");
 * </pre>
 */
public class EdgeRankingExpander {

    /**
     * Parse an edge label like '7->8' or 'ENTRY->5' to extract all block IDs/line numbers.
     *
     * @param edgeLabel The edge label string
     * @return List of block identifiers (as strings, could be line numbers or "ENTRY")
     */
    public List<String> parseEdgeLabel(String edgeLabel) {
        List<String> blocks = new ArrayList<>();
        String[] parts = edgeLabel.split("->");
        for (String part : parts) {
            String trimmed = part.trim();
            // Skip ENTRY as it's not a real block
            if (!trimmed.equals("ENTRY") && !trimmed.isEmpty()) {
                blocks.add(trimmed);
            }
        }
        return blocks;
    }

    /**
     * Extract the method prefix from a full node name.
     * E.g., "mypackage$App#mid(int,int,int):5#EDGE:ENTRY->5" returns "mypackage$App#mid(int,int,int)"
     */
    private String extractMethodPrefix(String nodeName) {
        // Look for the last colon before #EDGE or at the line number
        int edgeMarker = nodeName.indexOf("#EDGE:");
        if (edgeMarker > 0) {
            // New format: method:line#EDGE:from->to
            int colonBeforeLine = nodeName.lastIndexOf(':', edgeMarker - 1);
            if (colonBeforeLine > 0) {
                return nodeName.substring(0, colonBeforeLine);
            }
        }

        // Old format or fallback: find method:lineNumber
        int lastColon = nodeName.lastIndexOf(':');
        if (lastColon > 0) {
            return nodeName.substring(0, lastColon);
        }

        return nodeName;
    }

    /**
     * Expand edge-based ranking to block-level ranking.
     *
     * @param rankingFile Path to the EDGE ranking CSV file
     * @return Map from full block name (method:line) to maximum suspiciousness value
     * @throws IOException If file cannot be read
     */
    public Map<String, Double> expandEdgeRanking(String rankingFile) throws IOException {
        Map<String, Double> blockSuspiciousness = new HashMap<>();

        // Pattern for new format: :line#EDGE:from->to
        Pattern newFormatPattern = Pattern.compile(":([0-9]+)#EDGE:([A-Z0-9]+)->([0-9]+)$");
        // Pattern for old format: :from->to (where from and to are line numbers)
        Pattern oldFormatPattern = Pattern.compile(":([0-9]+)->([0-9]+)$");

        try (BufferedReader reader = new BufferedReader(new FileReader(rankingFile))) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                // Skip header
                if (firstLine) {
                    firstLine = false;
                    continue;
                }

                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(";");
                if (parts.length != 2) {
                    continue;
                }

                String name = parts[0];
                double suspiciousness = Double.parseDouble(parts[1]);

                String methodPrefix = extractMethodPrefix(name);
                List<String> lineNumbers = new ArrayList<>();

                // Try new format first: :line#EDGE:from->to
                Matcher newMatcher = newFormatPattern.matcher(name);
                if (newMatcher.find()) {
                    // The line number where probe is placed
                    String targetLine = newMatcher.group(1);
                    String fromLine = newMatcher.group(2); // Could be "ENTRY" or a number
                    String toLine = newMatcher.group(3);

                    // Add target line (toLine is the actual block)
                    lineNumbers.add(targetLine);
                    // Also add from line if it's a number (not ENTRY)
                    if (!fromLine.equals("ENTRY") && !fromLine.equals(targetLine)) {
                        lineNumbers.add(fromLine);
                    }
                } else {
                    // Try old format: :from->to
                    Matcher oldMatcher = oldFormatPattern.matcher(name);
                    if (oldMatcher.find()) {
                        lineNumbers.add(oldMatcher.group(1));
                        lineNumbers.add(oldMatcher.group(2));
                    }
                }

                // Assign this suspiciousness to ALL blocks on the path
                for (String lineNum : lineNumbers) {
                    String fullBlockName = methodPrefix + ":" + lineNum;

                    // Take the maximum suspiciousness if a block appears in multiple edges
                    if (!blockSuspiciousness.containsKey(fullBlockName)) {
                        blockSuspiciousness.put(fullBlockName, suspiciousness);
                    } else {
                        double currentSusp = blockSuspiciousness.get(fullBlockName);
                        blockSuspiciousness.put(fullBlockName, Math.max(currentSusp, suspiciousness));
                    }
                }
            }
        }

        return blockSuspiciousness;
    }

    /**
     * Get a sorted list of blocks by suspiciousness (highest first).
     *
     * @param blockSuspiciousness Map from block ID to suspiciousness
     * @return List of entries sorted by suspiciousness (descending)
     */
    public List<Map.Entry<String, Double>> getSortedBlocks(Map<String, Double> blockSuspiciousness) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(blockSuspiciousness.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue()); // Descending order
            }
        });
        return sorted;
    }

    /**
     * Print the expanded ranking for debugging.
     *
     * @param blockSuspiciousness Map from block ID to suspiciousness
     */
    public void printExpandedRanking(Map<String, Double> blockSuspiciousness) {
        System.out.println(new String(new char[80]).replace('\0', '='));
        System.out.println("Expanded SELECTIVE_CFG Ranking (Block-Level)");
        System.out.println(new String(new char[80]).replace('\0', '='));

        List<Map.Entry<String, Double>> sorted = getSortedBlocks(blockSuspiciousness);

        System.out.printf("%-15s %-20s%n", "Block ID", "Suspiciousness");
        System.out.println(new String(new char[35]).replace('\0', '-'));

        for (Map.Entry<String, Double> entry : sorted) {
            System.out.printf("%-15s %-20.16f%n", entry.getKey(), entry.getValue());
        }

        System.out.println();
        System.out.println("Total unique blocks: " + blockSuspiciousness.size());
    }

    /**
     * Main method for command-line usage.
     *
     * @param args Command line arguments [rankingFile]
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: EdgeRankingExpander <ranking-file>");
            System.err.println("Example: EdgeRankingExpander .gzoltar.selective-cfg/sfl/txt/ochiai.ranking.csv");
            System.exit(1);
        }

        String rankingFile = args[0];
        EdgeRankingExpander expander = new EdgeRankingExpander();

        try {
            Map<String, Double> blockSuspiciousness = expander.expandEdgeRanking(rankingFile);
            expander.printExpandedRanking(blockSuspiciousness);

            List<Map.Entry<String, Double>> sorted = expander.getSortedBlocks(blockSuspiciousness);
            if (!sorted.isEmpty()) {
                Map.Entry<String, Double> topBlock = sorted.get(0);
                System.out.println("Most suspicious block: " + topBlock.getKey() +
                                 " with suspiciousness " + topBlock.getValue());
            }

        } catch (IOException e) {
            System.err.println("Error reading ranking file: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

