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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores annotation information for an edge in the control flow graph.
 * Used by EDGE granularity to enable recovery of node coverage from edge coverage.
 */
public class EdgeAnnotation implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Unique identifier for this edge */
  private final int edgeId;

  /** Probe ID associated with this edge */
  private final int probeId;

  /** Method signature (class#method) */
  private final String methodKey;

  /** Source node identifier (e.g., "mypackage.App#mid:5") */
  private final String fromNode;

  /** Target node identifier (e.g., "mypackage.App#mid:7") */
  private final String toNode;

  /** List of nodes that were removed (can be inferred from this edge) */
  private final List<String> removedNodes;

  /** All lines covered when this edge is executed */
  private final List<Integer> coveredLines;

  public EdgeAnnotation(int edgeId, int probeId, String methodKey,
                        String fromNode, String toNode,
                        List<String> removedNodes, List<Integer> coveredLines) {
    this.edgeId = edgeId;
    this.probeId = probeId;
    this.methodKey = methodKey;
    this.fromNode = fromNode;
    this.toNode = toNode;
    this.removedNodes = new ArrayList<>(removedNodes);
    this.coveredLines = new ArrayList<>(coveredLines);
  }

  public int getEdgeId() {
    return edgeId;
  }

  public int getProbeId() {
    return probeId;
  }

  public String getMethodKey() {
    return methodKey;
  }

  public String getFromNode() {
    return fromNode;
  }

  public String getToNode() {
    return toNode;
  }

  public List<String> getRemovedNodes() {
    return new ArrayList<>(removedNodes);
  }

  public List<Integer> getCoveredLines() {
    return new ArrayList<>(coveredLines);
  }

  /**
   * Get the edge name in format "fromNode->toNode"
   */
  public String getEdgeName() {
    return fromNode + "->" + toNode;
  }

  @Override
  public String toString() {
    return "Edge#" + edgeId + " [" + fromNode + " -> " + toNode + "] removed=" + removedNodes;
  }

  /**
   * Convert to CSV line format.
   * Format: edgeId;probeId;methodKey;fromNode;toNode;removedNodes;coveredLines
   */
  public String toCsvLine() {
    StringBuilder sb = new StringBuilder();
    sb.append(edgeId).append(";");
    sb.append(probeId).append(";");
    sb.append(methodKey).append(";");
    sb.append(fromNode).append(";");
    sb.append(toNode).append(";");
    sb.append(String.join("|", removedNodes)).append(";");

    List<String> lineStrings = new ArrayList<>();
    for (Integer line : coveredLines) {
      lineStrings.add(line.toString());
    }
    sb.append(String.join("|", lineStrings));

    return sb.toString();
  }

  /**
   * Parse from CSV line.
   */
  public static EdgeAnnotation fromCsvLine(String line) {
    String[] parts = line.split(";", -1);
    if (parts.length < 7) {
      throw new IllegalArgumentException("Invalid CSV line: " + line);
    }

    int edgeId = Integer.parseInt(parts[0]);
    int probeId = Integer.parseInt(parts[1]);
    String methodKey = parts[2];
    String fromNode = parts[3];
    String toNode = parts[4];

    List<String> removedNodes = new ArrayList<>();
    if (!parts[5].isEmpty()) {
      for (String node : parts[5].split("\\|")) {
        if (!node.isEmpty()) {
          removedNodes.add(node);
        }
      }
    }

    List<Integer> coveredLines = new ArrayList<>();
    if (!parts[6].isEmpty()) {
      for (String lineStr : parts[6].split("\\|")) {
        if (!lineStr.isEmpty()) {
          coveredLines.add(Integer.parseInt(lineStr));
        }
      }
    }

    return new EdgeAnnotation(edgeId, probeId, methodKey, fromNode, toNode,
                              removedNodes, coveredLines);
  }
}
