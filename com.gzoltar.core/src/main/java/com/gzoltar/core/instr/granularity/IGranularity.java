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

public interface IGranularity {

  /**
   *
   * @param index
   * @param instrumentationSize
   * @return
   */
  public boolean instrumentAtIndex(final int index, final int instrumentationSize);

  /**
   *
   * @return
   */
  public boolean stopInstrumenting();

  /**
   * Gets a unique suffix to append to the node name for disambiguation.
   * This is useful when multiple instrumentation points exist on the same line.
   *
   * @return A suffix string (e.g., "#PRS5"), or empty string if no suffix is needed
   */
  public String getNodeSuffix();

  /**
   * Check if the current index represents a decision point for instrumentation,
   * even if this granularity chooses not to actually instrument it.
   * This is used to maintain consistent bytecode offsets across different granularities.
   *
   * For selective instrumentation strategies (like SELECTIVE_CFG), this method should
   * return true for ALL potential instrumentation points (both instrumented and skipped),
   * while instrumentAtIndex only returns true for points that should actually be instrumented.
   *
   * Default implementation returns the same value as instrumentAtIndex, which is correct
   * for non-selective strategies (LINE, METHOD, BASICBLOCK).
   *
   * @param index current bytecode position
   * @param instrumentationSize cumulative size of already inserted instrumentation
   * @return true if this is a decision point that should be counted for offset calculation
   */
  public boolean isDecisionPoint(final int index, final int instrumentationSize);

  /**
   * NEW: Gets the map of edges for edge-based granularities.
   * The map should contain pairs of (sourceBlockId, destBlockId) as keys
   * and the unique edge ID (probe index) as values.
   *
   * @return A map representing the edges of a simplified CFG, or an empty map if not applicable.
   */
  public java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> getEdges();

  /**
   * NEW: Gets the map of human-readable labels for edges.
   * The key is the same as getEdges(), the value is the label string.
   *
   * @return A map of edge labels, or an empty map if not applicable.
   */
  public java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, String> getEdgeLabels();

  /**
   * NEW: Gets the complete path (list of BasicBlockNodes) for each edge.
   * This allows expanding edge-based rankings back to individual block/line rankings.
   *
   * @return A map of edge paths, or an empty map if not applicable.
   */
  public java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, java.util.List<com.gzoltar.core.instr.cfg.BasicBlockNode>> getEdgePaths();

  /**
   * Allows granularity strategies to add custom fields to the instrumented class.
   * For example, m PRS Edge adds an edge lookup table field.
   *
   * @param ctClass The class being instrumented
   * @throws Exception if field addition fails
   */
  public void addCustomFields(javassist.CtClass ctClass) throws Exception;

  /**
   * Allows granularity strategies to add custom initialization code to the class static initializer.
   * This is called after all probes have been registered and their indices are known.
   *
   * @param ctClass The class being instrumented
   * @param edgeToProbeIndex Mapping from edges to their probe array indices (for edge-based granularities)
   * @throws Exception if initialization fails
   */
  public void initializeCustomFields(javassist.CtClass ctClass,
      java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> edgeToProbeIndex) throws Exception;

  /**
   * Allows granularity strategies to generate custom instrumentation code.
   * If this returns null, the standard probe instrumentation code will be used.
   *
   * @param ctClass The class being instrumented
   * @param constPool The constant pool for bytecode generation
   * @param context Additional context (e.g., blockId, probe, etc.) as a generic map
   * @return Custom bytecode, or null to use default instrumentation
   * @throws Exception if code generation fails
   */
  public javassist.bytecode.Bytecode generateCustomInstrumentationCode(
      javassist.CtClass ctClass,
      javassist.bytecode.ConstPool constPool,
      java.util.Map<String, Object> context) throws Exception;

  /**
   * Determines if this granularity uses edge-based instrumentation (vs node-based).
   * Edge-based granularities track transitions between blocks instead of block hits.
   *
   * @return true if this is an edge-based granularity
   */
  public boolean isEdgeBased();

}
