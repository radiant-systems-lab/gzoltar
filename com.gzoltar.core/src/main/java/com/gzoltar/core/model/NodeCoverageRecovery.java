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
package com.gzoltar.core.model;

import java.util.*;

/**
 * Recovers node-level coverage from edge-level coverage using edge annotations.
 *
 * For EDGE granularity, probes are placed on edges instead of nodes.
 * This class provides methods to recover which nodes were covered based on
 * which edges were executed.
 *
 * Recovery logic:
 * - If edge A->B is covered, then:
 *   - Node B is covered
 *   - All removed nodes between A and B are covered
 *   - All lines in coveredLines are covered
 */
public class NodeCoverageRecovery {

  private final EdgeAnnotationRegistry registry;

  /** Map from node name to list of edges that cover it */
  private Map<String, List<EdgeAnnotation>> nodeToEdges;

  /** Map from line (method:line) to list of edges that cover it */
  private Map<String, List<EdgeAnnotation>> lineToEdges;

  public NodeCoverageRecovery() {
    this(EdgeAnnotationRegistry.getInstance());
  }

  public NodeCoverageRecovery(EdgeAnnotationRegistry registry) {
    this.registry = registry;
    buildMappings();
  }

  /**
   * Build mappings from nodes/lines to edges.
   */
  private void buildMappings() {
    nodeToEdges = new HashMap<>();
    lineToEdges = new HashMap<>();

    for (EdgeAnnotation edge : registry.getAllAnnotations()) {
      // Target node is covered by this edge
      addNodeMapping(edge.getToNode(), edge);

      // Removed nodes are covered by this edge
      for (String removedNode : edge.getRemovedNodes()) {
        addNodeMapping(removedNode, edge);
      }

      // Covered lines are covered by this edge
      String methodKey = edge.getMethodKey();
      for (Integer line : edge.getCoveredLines()) {
        String lineKey = methodKey + ":" + line;
        addLineMapping(lineKey, edge);
      }
    }
  }

  private void addNodeMapping(String node, EdgeAnnotation edge) {
    nodeToEdges.computeIfAbsent(node, k -> new ArrayList<>()).add(edge);
  }

  private void addLineMapping(String lineKey, EdgeAnnotation edge) {
    lineToEdges.computeIfAbsent(lineKey, k -> new ArrayList<>()).add(edge);
  }

  /**
   * Recover node coverage from edge coverage.
   *
   * @param edgeCoverage boolean array indexed by probe ID (true = edge covered)
   * @return set of covered node names
   */
  public Set<String> recoverNodeCoverage(boolean[] edgeCoverage) {
    Set<String> coveredNodes = new HashSet<>();

    for (EdgeAnnotation edge : registry.getAllAnnotations()) {
      int probeId = edge.getProbeId();
      if (probeId < edgeCoverage.length && edgeCoverage[probeId]) {
        // Edge is covered - add target node and removed nodes
        coveredNodes.add(edge.getToNode());
        coveredNodes.addAll(edge.getRemovedNodes());
      }
    }

    return coveredNodes;
  }

  /**
   * Recover line coverage from edge coverage.
   *
   * @param edgeCoverage boolean array indexed by probe ID (true = edge covered)
   * @return map from method:line to coverage status
   */
  public Map<String, Boolean> recoverLineCoverage(boolean[] edgeCoverage) {
    Map<String, Boolean> lineCoverage = new HashMap<>();

    // Initialize all known lines as not covered
    for (String lineKey : lineToEdges.keySet()) {
      lineCoverage.put(lineKey, false);
    }

    // Mark lines as covered based on edge coverage
    for (EdgeAnnotation edge : registry.getAllAnnotations()) {
      int probeId = edge.getProbeId();
      if (probeId < edgeCoverage.length && edgeCoverage[probeId]) {
        String methodKey = edge.getMethodKey();
        for (Integer line : edge.getCoveredLines()) {
          lineCoverage.put(methodKey + ":" + line, true);
        }
      }
    }

    return lineCoverage;
  }

  /**
   * Check if a specific node is covered based on edge coverage.
   *
   * @param nodeName the node name to check
   * @param edgeCoverage boolean array indexed by probe ID
   * @return true if the node is covered
   */
  public boolean isNodeCovered(String nodeName, boolean[] edgeCoverage) {
    List<EdgeAnnotation> edges = nodeToEdges.get(nodeName);
    if (edges == null || edges.isEmpty()) {
      return false;
    }

    for (EdgeAnnotation edge : edges) {
      int probeId = edge.getProbeId();
      if (probeId < edgeCoverage.length && edgeCoverage[probeId]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if a specific line is covered based on edge coverage.
   *
   * @param methodKey method identifier (class#method)
   * @param lineNumber line number
   * @param edgeCoverage boolean array indexed by probe ID
   * @return true if the line is covered
   */
  public boolean isLineCovered(String methodKey, int lineNumber, boolean[] edgeCoverage) {
    String lineKey = methodKey + ":" + lineNumber;
    List<EdgeAnnotation> edges = lineToEdges.get(lineKey);
    if (edges == null || edges.isEmpty()) {
      return false;
    }

    for (EdgeAnnotation edge : edges) {
      int probeId = edge.getProbeId();
      if (probeId < edgeCoverage.length && edgeCoverage[probeId]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get all nodes that can be covered.
   */
  public Set<String> getAllNodes() {
    return registry.getAllNodes();
  }

  /**
   * Get all edges.
   */
  public Collection<EdgeAnnotation> getAllEdges() {
    return registry.getAllAnnotations();
  }

  /**
   * Calculate Ochiai suspiciousness for each node based on edge coverage data.
   *
   * @param testResults list of test results (true = passed, false = failed)
   * @param coverageMatrix list of edge coverage arrays for each test
   * @return map from node name to Ochiai suspiciousness score
   */
  public Map<String, Double> calculateNodeOchiai(List<Boolean> testResults,
                                                  List<boolean[]> coverageMatrix) {
    Map<String, Double> ranking = new LinkedHashMap<>();
    Set<String> allNodes = getAllNodes();

    int totalFailed = 0;
    for (Boolean passed : testResults) {
      if (!passed) totalFailed++;
    }

    for (String node : allNodes) {
      int ef = 0; // executed by failed tests
      int ep = 0; // executed by passed tests

      for (int t = 0; t < testResults.size(); t++) {
        boolean covered = isNodeCovered(node, coverageMatrix.get(t));
        boolean passed = testResults.get(t);

        if (covered && !passed) ef++;
        else if (covered && passed) ep++;
      }

      double ochiai = 0.0;
      if (ef > 0 && totalFailed > 0) {
        ochiai = ef / Math.sqrt((double) totalFailed * (ef + ep));
      }
      ranking.put(node, ochiai);
    }

    // Sort by suspiciousness descending
    List<Map.Entry<String, Double>> sorted = new ArrayList<>(ranking.entrySet());
    sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

    Map<String, Double> sortedRanking = new LinkedHashMap<>();
    for (Map.Entry<String, Double> entry : sorted) {
      sortedRanking.put(entry.getKey(), entry.getValue());
    }

    return sortedRanking;
  }
}
