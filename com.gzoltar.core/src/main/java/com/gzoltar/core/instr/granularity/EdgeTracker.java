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
package com.gzoltar.core.instr.granularity;

/**
 * Runtime helper for edge-based coverage tracking.
 * This class simplifies edge instrumentation by providing a simple API.
 */
public class EdgeTracker {

    /**
     * Track an edge transition from the last visited block to the current block.
     * This method is called at the beginning of each instrumented basic block.
     *
     * @param lookupTable The edge lookup table for the current class
     * @param probes The probe array for the current class (boolean[] in GZoltar)
     * @param lastHitNodeId ThreadLocal storing the last visited block ID
     * @param currentBlockId The ID of the current block being entered
     */
    public static void trackEdge(int[][] lookupTable, boolean[] probes,
                                  ThreadLocal<Integer> lastHitNodeId, int currentBlockId) {
        if (lookupTable == null || probes == null || lastHitNodeId == null) {
            return;
        }

        try {
            // 1. Get last hit node ID from ThreadLocal
            Integer lastNode = (Integer) lastHitNodeId.get();

            // 2. If a previous node was hit in this thread, check for a valid edge
            if (lastNode != null && lastNode >= 0) {
                // Check bounds to prevent ArrayIndexOutOfBoundsException
                if (lastNode < lookupTable.length && currentBlockId < lookupTable[lastNode].length) {
                    int probeIndex = lookupTable[lastNode][currentBlockId];

                    // 3. If a valid edge exists (probeIndex >= 0), mark the probe
                    if (probeIndex >= 0 && probeIndex < probes.length) {
                        probes[probeIndex] = true;
                    }
                }
            }

            // 4. Update ThreadLocal with the current block ID for the next edge
            lastHitNodeId.set(currentBlockId);

        } catch (Exception e) {
            // Silently ignore exceptions to minimize runtime overhead
        }
    }

    /**
     * Initialize an edge lookup table with the given edges.
     * This is called during class initialization to set up the lookup table.
     *
     * @param table The 2D array to initialize (already allocated)
     * @param edges Array of [srcId, destId, probeIndex] tuples
     */
    public static void initializeLookupTable(int[][] table, int[][] edges) {
        // Initialize all elements to -1
        for (int i = 0; i < table.length; i++) {
            for (int j = 0; j < table[i].length; j++) {
                table[i][j] = -1;
            }
        }

        // Populate actual edge mappings
        for (int i = 0; i < edges.length; i++) {
            int srcId = edges[i][0];
            int destId = edges[i][1];
            int probeIndex = edges[i][2];
            table[srcId][destId] = probeIndex;
        }
    }
}

