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

import java.io.*;
import java.util.*;

/**
 * Global registry for edge annotations.
 * Used by EDGE granularity to store and retrieve edge metadata for node recovery.
 */
public class EdgeAnnotationRegistry {

  private static EdgeAnnotationRegistry instance = null;

  /** All registered edge annotations, indexed by probe ID */
  private final Map<Integer, EdgeAnnotation> annotationsByProbeId = new LinkedHashMap<>();

  /** All registered edge annotations, indexed by edge name */
  private final Map<String, EdgeAnnotation> annotationsByEdgeName = new LinkedHashMap<>();

  /** All unique node names (for spectra) */
  private final Set<String> allNodes = new TreeSet<>();

  /** Output directory for saving annotations */
  private String outputDirectory = ".";

  private EdgeAnnotationRegistry() {
  }

  /**
   * Get the singleton instance.
   */
  public static synchronized EdgeAnnotationRegistry getInstance() {
    if (instance == null) {
      instance = new EdgeAnnotationRegistry();
    }
    return instance;
  }

  /**
   * Reset the registry (for testing purposes).
   */
  public static synchronized void reset() {
    instance = null;
  }

  /**
   * Set the output directory for saving annotations.
   */
  public void setOutputDirectory(String dir) {
    this.outputDirectory = dir;
    System.err.println("[DEBUG AnnotationRegistry] Output directory set to: " + dir);
  }

  /**
   * Register an edge annotation.
   */
  public synchronized void register(EdgeAnnotation annotation) {
    annotationsByProbeId.put(annotation.getProbeId(), annotation);
    annotationsByEdgeName.put(annotation.getEdgeName(), annotation);

    // Also register nodes
    allNodes.add(annotation.getFromNode());
    allNodes.add(annotation.getToNode());
    for (String removedNode : annotation.getRemovedNodes()) {
      allNodes.add(removedNode);
    }
  }

  /**
   * Get annotation by probe ID.
   */
  public EdgeAnnotation getByProbeId(int probeId) {
    return annotationsByProbeId.get(probeId);
  }

  /**
   * Get annotation by edge name.
   */
  public EdgeAnnotation getByEdgeName(String edgeName) {
    return annotationsByEdgeName.get(edgeName);
  }

  /**
   * Get all annotations.
   */
  public Collection<EdgeAnnotation> getAllAnnotations() {
    return new ArrayList<>(annotationsByProbeId.values());
  }

  /**
   * Get all node names.
   */
  public Set<String> getAllNodes() {
    return new TreeSet<>(allNodes);
  }

  /**
   * Get number of registered annotations.
   */
  public int size() {
    return annotationsByProbeId.size();
  }

  /**
   * Save annotations to CSV file.
   */
  public void saveToFile(String filename) throws IOException {
    File file = new File(outputDirectory, filename);
    file.getParentFile().mkdirs();

    try (PrintWriter pw = new PrintWriter(file)) {
      pw.println("edge_id;probe_id;method;from_node;to_node;removed_nodes;covered_lines");
      for (EdgeAnnotation annotation : annotationsByProbeId.values()) {
        pw.println(annotation.toCsvLine());
      }
    }
    System.err.println("[DEBUG AnnotationRegistry] Saved " + size() + " annotations to: " + file.getAbsolutePath());
  }

  /**
   * Save node spectra to CSV file.
   */
  public void saveNodeSpectra(String filename) throws IOException {
    File file = new File(outputDirectory, filename);
    file.getParentFile().mkdirs();

    try (PrintWriter pw = new PrintWriter(file)) {
      pw.println("name");
      for (String node : allNodes) {
        pw.println(node);
      }
    }
    System.err.println("[DEBUG AnnotationRegistry] Saved " + allNodes.size() + " nodes to: " + file.getAbsolutePath());
  }

  /**
   * Load annotations from CSV file.
   */
  public void loadFromFile(String filename) throws IOException {
    File file = new File(outputDirectory, filename);
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line = br.readLine(); // Skip header
      while ((line = br.readLine()) != null) {
        if (!line.trim().isEmpty()) {
          EdgeAnnotation annotation = EdgeAnnotation.fromCsvLine(line);
          register(annotation);
        }
      }
    }
    System.err.println("[DEBUG AnnotationRegistry] Loaded " + size() + " annotations from: " + file.getAbsolutePath());
  }

  /**
   * Save all annotations to the output directory.
   * Called at the end of instrumentation/test execution.
   */
  public void saveAll() {
    if (annotationsByProbeId.isEmpty()) {
      System.err.println("[DEBUG AnnotationRegistry] No annotations to save");
      return;
    }

    try {
      saveToFile("edges.csv");
      saveNodeSpectra("node_spectra.csv");
    } catch (IOException e) {
      System.err.println("[ERROR AnnotationRegistry] Failed to save annotations: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
