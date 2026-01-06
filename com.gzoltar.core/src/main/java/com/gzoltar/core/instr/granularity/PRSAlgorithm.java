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

import java.util.*;

/**
 * Path Recovery Set (PRS) Algorithm implementation.
 *
 * This is a framework-agnostic implementation that can be used by both
 * ASM-based and Javassist-based instrumenters.
 *
 * The PRS algorithm identifies which basic blocks can be "removed" (not instrumented)
 * while still maintaining the ability to recover full path information from edge coverage.
 *
 * A node is removable if:
 * 1. It has at least one predecessor (not an entry node)
 * 2. It has at least one successor (not an exit node)
 * 3. It is not a direct successor of an entry node
 * 4. Removing it would not create duplicate edges (edge ambiguity)
 */
public class PRSAlgorithm {

    /**
     * Result of PRS algorithm execution.
     */
    public static class PRSResult {
        /** Set of block IDs that can be removed (not instrumented) */
        public final Set<Integer> removableBlocks;

        /** Updated out-edges after virtual node removal */
        public final Map<Integer, Set<Integer>> simplifiedOutEdges;

        /** Updated in-edges after virtual node removal */
        public final Map<Integer, Set<Integer>> simplifiedInEdges;

        public PRSResult(Set<Integer> removableBlocks,
                        Map<Integer, Set<Integer>> simplifiedOutEdges,
                        Map<Integer, Set<Integer>> simplifiedInEdges) {
            this.removableBlocks = removableBlocks;
            this.simplifiedOutEdges = simplifiedOutEdges;
            this.simplifiedInEdges = simplifiedInEdges;
        }
    }

    /**
     * Simple representation of a basic block for PRS algorithm.
     */
    public static class Block {
        public final int id;
        public final Set<Integer> predecessors;
        public final Set<Integer> successors;

        public Block(int id, Set<Integer> predecessors, Set<Integer> successors) {
            this.id = id;
            this.predecessors = predecessors;
            this.successors = successors;
        }
    }

    /**
     * Find removable blocks using the PRS algorithm.
     *
     * @param blocks List of basic blocks with their predecessor/successor information
     * @return PRSResult containing removable blocks and simplified edges
     */
    public static PRSResult findRemovableBlocks(List<Block> blocks) {
        Set<Integer> removable = new HashSet<>();

        // Build mutable edge sets
        Map<Integer, Set<Integer>> outEdges = new HashMap<>();
        Map<Integer, Set<Integer>> inEdges = new HashMap<>();

        for (Block block : blocks) {
            outEdges.put(block.id, new HashSet<>(block.successors));
            inEdges.put(block.id, new HashSet<>(block.predecessors));
        }

        // Entry's direct successors are NOT removable
        Set<Integer> entrySuccessors = new HashSet<>();
        for (Block block : blocks) {
            if (block.predecessors.isEmpty()) {
                entrySuccessors.addAll(block.successors);
            }
        }

        // Sort by inDegree * outDegree (BOTH strategy) - prioritize removing low-impact nodes
        List<Block> sortedBlocks = new ArrayList<>(blocks);
        sortedBlocks.sort((a, b) -> {
            int scoreA = a.predecessors.size() * a.successors.size();
            int scoreB = b.predecessors.size() * b.successors.size();
            return Integer.compare(scoreA, scoreB);
        });

        for (Block block : sortedBlocks) {
            int inDegree = inEdges.get(block.id).size();
            int outDegree = outEdges.get(block.id).size();

            // Entry nodes (in-degree = 0) are NOT removable
            if (inDegree == 0) continue;
            // Exit nodes (out-degree = 0) are NOT removable
            if (outDegree == 0) continue;
            // Entry's direct successors are NOT removable
            if (entrySuccessors.contains(block.id)) continue;

            // Check if removal would create duplicate edges
            boolean canRemove = true;
            Map<Integer, Set<Integer>> newEdges = new HashMap<>();

            for (int pred : inEdges.get(block.id)) {
                for (int succ : outEdges.get(block.id)) {
                    if (outEdges.get(pred).contains(succ)) {
                        // Edge already exists - would create ambiguity
                        canRemove = false;
                        break;
                    }
                    newEdges.computeIfAbsent(pred, k -> new HashSet<>()).add(succ);
                }
                if (!canRemove) break;
            }

            if (canRemove) {
                removable.add(block.id);
                // Update edge sets (virtual removal)
                for (Map.Entry<Integer, Set<Integer>> entry : newEdges.entrySet()) {
                    int pred = entry.getKey();
                    for (int succ : entry.getValue()) {
                        outEdges.get(pred).add(succ);
                        inEdges.get(succ).add(pred);
                    }
                }
            }
        }

        return new PRSResult(removable, outEdges, inEdges);
    }
}
