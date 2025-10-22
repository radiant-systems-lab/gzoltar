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
     * Finds a set of nodes that can be removed from the graph for instrumentation purposes
     * without losing path coverage information. This is an implementation of a greedy heuristic
     * algorithm for the Path Recovery Set problem.
     *
     * @param order The ordering strategy for processing nodes
     * @return A set of nodes that can be safely removed (not instrumented).
     */
    public Set<BasicBlockNode> findMinimalNodes(PathRecoveryOrder order) {
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
            // Head nodes (in-degree = 0) and tail nodes (out-degree = 0) can potentially be removed
            // BUT: if the graph has only 1 node, we must keep it (otherwise no instrumentation at all)
            if (!newInEdges.containsKey(n) || newInEdges.get(n).size() == 0
                    || !newOutEdges.containsKey(n) || newOutEdges.get(n).size() == 0) {
                // Only add to removable set if there are other nodes in the graph
                if (nodes.size() > 1) {
                    ret.add(n);
                }
                continue;
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
        return ret;
    }

    /**
     * Helper method to merge temporary edges into the main edge structures.
     * This matches the C++ mergeEdges function exactly.
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
}
