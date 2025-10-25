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
package com.gzoltar.core.instr.cfg;

import java.util.*;
import java.util.Comparator;
import java.util.Collections;


/**
 * Represents the Control Flow Graph for a single method.
 */
public class ControlFlowGraph {
    public final String methodName;
    public final Map<Integer, BasicBlockNode> nodes = new HashMap<Integer, BasicBlockNode>();
    public final Map<BasicBlockNode, Set<BasicBlockNode>> outEdges = new HashMap<BasicBlockNode, Set<BasicBlockNode>>();
    public final Map<BasicBlockNode, Set<BasicBlockNode>> inEdges = new HashMap<BasicBlockNode, Set<BasicBlockNode>>();

    /**
     * Represents an edge in the simplified/reduced CFG.
     * Each edge connects two instrumented nodes and may represent a path
     * through multiple removable nodes in the original CFG.
     */
    public static class SimplifiedEdge {
        public final BasicBlockNode source;
        public final BasicBlockNode destination;
        public final List<BasicBlockNode> path;  // Complete path including source and dest
        public final String label;               // e.g., "14->15" (line numbers)

        public SimplifiedEdge(BasicBlockNode src, BasicBlockNode dst,
                             List<BasicBlockNode> path, String label) {
            this.source = src;
            this.destination = dst;
            this.path = path;
            this.label = label;
        }

        @Override
        public String toString() {
            return "Edge[" + source.id + " -> " + destination.id + "]: " + label;
        }
    }

    /**
     * Result of PRS algorithm, containing both removable nodes and simplified edges.
     */
    public static class PRSResult {
        public final Set<BasicBlockNode> removableNodes;
        public final Map<BasicBlockNode, Set<BasicBlockNode>> simplifiedOutEdges;

        public PRSResult(Set<BasicBlockNode> removableNodes,
                        Map<BasicBlockNode, Set<BasicBlockNode>> simplifiedOutEdges) {
            this.removableNodes = removableNodes;
            this.simplifiedOutEdges = simplifiedOutEdges;
        }
    }

    public ControlFlowGraph(String methodName) {
        this.methodName = methodName;
    }

    public void insertNode(BasicBlockNode node) {
        if (!nodes.containsKey(node.offset)) {
            nodes.put(node.offset, node);
            outEdges.put(node, new HashSet<BasicBlockNode>());
            inEdges.put(node, new HashSet<BasicBlockNode>());
        }
    }

    public void insertEdge(BasicBlockNode from, BasicBlockNode to) {
        if (!outEdges.containsKey(from)) {
            outEdges.put(from, new HashSet<BasicBlockNode>());
        }
        outEdges.get(from).add(to);
        if (!inEdges.containsKey(to)) {
            inEdges.put(to, new HashSet<BasicBlockNode>());
        }
        inEdges.get(to).add(from);
    }

    /**
     * Finds minimal nodes and returns both removable nodes and simplified edges.
     * This is more efficient than calling findMinimalNodes() and buildSimplifiedEdges() separately.
     *
     * @param order The ordering strategy for processing nodes
     * @return PRSResult containing removable nodes and simplified edges
     */
    public PRSResult findMinimalNodesWithEdges(PathRecoveryOrder order) {
        PRSResult result = findMinimalNodesInternal(order);
        return result;
    }

    /**
     * Finds a set of nodes that can be removed from the graph for instrumentation purposes
     * without losing path coverage information. This is an implementation of a greedy heuristic
     * algorithm for the Path Recovery Set problem.
     *
     * @param order The ordering strategy for processing nodes
     * @return A set of nodes that can be safely removed (not instrumented).
     */
    public Set<BasicBlockNode> findMinimalNodes(PathRecoveryOrder order) {
        return findMinimalNodesInternal(order).removableNodes;
    }

    /**
     * Internal implementation of PRS algorithm that returns both removable nodes and edges.
     */
    private PRSResult findMinimalNodesInternal(PathRecoveryOrder order) {
        Set<BasicBlockNode> ret = new HashSet<BasicBlockNode>();
        List<BasicBlockNode> v = new ArrayList<BasicBlockNode>();
        for (BasicBlockNode node : this.nodes.values()) {
            v.add(node);
        }

        // Apply ordering strategy
        switch (order) {
            case RANDOM:
                Collections.shuffle(v);
                break;
            case INDEGREE:
                Collections.sort(v, new Comparator<BasicBlockNode>() {
                    public int compare(BasicBlockNode a, BasicBlockNode b) {
                        int inDegreeA = inEdges.containsKey(a) ? inEdges.get(a).size() : 0;
                        int inDegreeB = inEdges.containsKey(b) ? inEdges.get(b).size() : 0;
                        return Integer.compare(inDegreeA, inDegreeB);
                    }
                });
                break;
            case OUTDEGREE:
                Collections.sort(v, new Comparator<BasicBlockNode>() {
                    public int compare(BasicBlockNode a, BasicBlockNode b) {
                        int outDegreeA = outEdges.containsKey(a) ? outEdges.get(a).size() : 0;
                        int outDegreeB = outEdges.containsKey(b) ? outEdges.get(b).size() : 0;
                        return Integer.compare(outDegreeA, outDegreeB);
                    }
                });
                break;
            case BOTH:
                Collections.sort(v, new Comparator<BasicBlockNode>() {
                    public int compare(BasicBlockNode a, BasicBlockNode b) {
                        int inDegreeA = inEdges.containsKey(a) ? inEdges.get(a).size() : 0;
                        int outDegreeA = outEdges.containsKey(a) ? outEdges.get(a).size() : 0;
                        int inDegreeB = inEdges.containsKey(b) ? inEdges.get(b).size() : 0;
                        int outDegreeB = outEdges.containsKey(b) ? outEdges.get(b).size() : 0;
                        return Integer.compare(inDegreeA * outDegreeA, inDegreeB * outDegreeB);
                    }
                });
                break;
            default:
                Collections.shuffle(v);
                break;
        }

        // Create copies of edge structures
        Map<BasicBlockNode, Set<BasicBlockNode>> newOutEdges = new HashMap<BasicBlockNode, Set<BasicBlockNode>>();
        for (Map.Entry<BasicBlockNode, Set<BasicBlockNode>> entry : this.outEdges.entrySet()) {
            newOutEdges.put(entry.getKey(), new HashSet<BasicBlockNode>(entry.getValue()));
        }

        Map<BasicBlockNode, Set<BasicBlockNode>> newInEdges = new HashMap<BasicBlockNode, Set<BasicBlockNode>>();
        for (Map.Entry<BasicBlockNode, Set<BasicBlockNode>> entry : this.inEdges.entrySet()) {
            newInEdges.put(entry.getKey(), new HashSet<BasicBlockNode>(entry.getValue()));
        }

        Map<BasicBlockNode, Set<BasicBlockNode>> tmpOutEdges = new HashMap<BasicBlockNode, Set<BasicBlockNode>>();
        boolean flag = false;

        for (BasicBlockNode n : v) {
            flag = false;
            tmpOutEdges.clear();  // Clear tmpOutEdges at the start of each iteration

            // Head nodes (in-degree = 0) and tail nodes (out-degree = 0) are NOT removable
            if (!newInEdges.containsKey(n) || newInEdges.get(n).size() == 0
                    || !newOutEdges.containsKey(n) || newOutEdges.get(n).size() == 0) {
                continue;  // Skip these nodes (keep them instrumented)
            }

            for (BasicBlockNode src : newInEdges.get(n)) {
                for (BasicBlockNode dst : newOutEdges.get(n)) {
                    if (newOutEdges.containsKey(src) && newOutEdges.get(src).contains(dst)) {
                        flag = true;
                        break;
                    } else {
                        if (!tmpOutEdges.containsKey(src)) {
                            tmpOutEdges.put(src, new HashSet<BasicBlockNode>());
                        }
                        tmpOutEdges.get(src).add(dst);
                    }
                }
                if (flag) break;
            }

            if (!flag) {
                ret.add(n);
                mergeEdges(tmpOutEdges, newOutEdges, newInEdges);
            }
        }
        return new PRSResult(ret, newOutEdges);
    }

    /**
     * Helper method to merge temporary edges into the main edge structures.
     */
    private void mergeEdges(Map<BasicBlockNode, Set<BasicBlockNode>> tmpOutEdges,
                           Map<BasicBlockNode, Set<BasicBlockNode>> outEdges,
                           Map<BasicBlockNode, Set<BasicBlockNode>> inEdges) {
        for (Map.Entry<BasicBlockNode, Set<BasicBlockNode>> entry : tmpOutEdges.entrySet()) {
            BasicBlockNode src = entry.getKey();
            Set<BasicBlockNode> dsts = entry.getValue();

            if (!outEdges.containsKey(src)) {
                outEdges.put(src, new HashSet<BasicBlockNode>());
            }

            for (BasicBlockNode dst : dsts) {
                outEdges.get(src).add(dst);
                if (!inEdges.containsKey(dst)) {
                    inEdges.put(dst, new HashSet<BasicBlockNode>());
                }
                inEdges.get(dst).add(src);
            }
        }
        tmpOutEdges.clear();
    }

    /**
     * Simplified version: build edges directly from PRS result without BFS.
     * This is faster but doesn't include full path information.
     * Use expandEdgePath() later if you need the complete path.
     *
     * @param prsResult The result from findMinimalNodesWithEdges()
     * @param methodInfo The method info for line number mapping
     * @return List of SimplifiedEdge objects with basic labels (source->destination only)
     */
    public List<SimplifiedEdge> buildSimplifiedEdgesSimple(PRSResult prsResult,
                                                            javassist.bytecode.MethodInfo methodInfo) {
        List<SimplifiedEdge> simplifiedEdges = new ArrayList<>();

        // FINAL CORRECTED APPROACH: Track edges between two INSTRUMENTED nodes
        // Key insight:
        // 1. Both source and destination must be instrumented (non-removable)
        // 2. We MUST use simplifiedOutEdges which includes transitive edges created by PRS
        // 3. Transitive edges are NECESSARY when the original path goes through removable nodes
        for (Map.Entry<BasicBlockNode, Set<BasicBlockNode>> entry : prsResult.simplifiedOutEdges.entrySet()) {
            BasicBlockNode src = entry.getKey();

            // Check if source node is instrumented (non-removable)
            boolean srcRemovable = prsResult.removableNodes.contains(src);
            if (srcRemovable) {
                // Skip edges from removable source - they can't be tracked at runtime
                continue;
            }

            for (BasicBlockNode dst : entry.getValue()) {
                // Check if destination node is instrumented (non-removable)
                boolean dstRemovable = prsResult.removableNodes.contains(dst);
                if (dstRemovable) {
                    // Skip edges to removable destination nodes
                    continue;
                }

                // Keep this edge (both nodes instrumented)
                // This includes both original edges and transitive edges created by PRS
                int srcLine = methodInfo.getLineNumber(src.offset);
                int dstLine = methodInfo.getLineNumber(dst.offset);

                String label;
                if (srcLine > 0 && dstLine > 0) {
                    label = srcLine + "->" + dstLine;
                } else {
                    label = src.id + "->" + dst.id;
                }

                // Path is null - will be expanded on demand
                simplifiedEdges.add(new SimplifiedEdge(src, dst, null, label));
            }
        }

        return simplifiedEdges;
    }
}
