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

import javassist.CtClass;
import javassist.bytecode.MethodInfo;

public abstract class AbstractGranularity implements IGranularity {

  protected CtClass ctClass;

  protected MethodInfo methodInfo;

  /**
   *
   * @param ctClass
   * @param methodInfo
   */
  public AbstractGranularity(final CtClass ctClass, final MethodInfo methodInfo) {
    this.ctClass = ctClass;
    this.methodInfo = methodInfo;
  }

  /**
   * Default implementation returns empty string.
   * Subclasses can override to provide custom node suffixes.
   */
  @Override
  public String getNodeSuffix() {
    return "";
  }

  /**
   * Default implementation returns false (no decision points that aren't instrumented).
   * This is correct for non-selective strategies (LINE, METHOD, BASICBLOCK).
   * Selective strategies (SELECTIVE_CFG) should override this method.
   */
  @Override
  public boolean isDecisionPoint(final int index, final int instrumentationSize) {
    return false;
  }

  /**
   * Default implementation returns an empty map.
   * Subclasses can override to provide edge information for edge-based coverage.
   */
  @Override
  public java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> getEdges() {
    return java.util.Collections.emptyMap();
  }

  /**
   * Default implementation returns an empty map.
   * Subclasses can override to provide edge label information.
   */
  @Override
  public java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, String> getEdgeLabels() {
    return java.util.Collections.emptyMap();
  }

  /**
   * Default implementation returns an empty map.
   * Subclasses can override to provide edge path information for expanding edges to blocks.
   */
  @Override
  public java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, java.util.List<com.gzoltar.core.instr.cfg.BasicBlockNode>> getEdgePaths() {
    return java.util.Collections.emptyMap();
  }

  /**
   * Default implementation does nothing.
   * Subclasses can override to add custom fields (e.g., lookup tables).
   */
  @Override
  public void addCustomFields(javassist.CtClass ctClass) throws Exception {
    // Default: no custom fields
  }

  /**
   * Default implementation does nothing.
   * Subclasses can override to initialize custom fields in <clinit>.
   */
  @Override
  public void initializeCustomFields(javassist.CtClass ctClass,
      java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> edgeToProbeIndex) throws Exception {
    // Default: no custom initialization
  }

  /**
   * Default implementation returns null, indicating standard probe instrumentation should be used.
   * Subclasses can override to provide custom instrumentation code.
   */
  @Override
  public javassist.bytecode.Bytecode generateCustomInstrumentationCode(
      javassist.CtClass ctClass,
      javassist.bytecode.ConstPool constPool,
      java.util.Map<String, Object> context) throws Exception {
    return null; // Use standard instrumentation
  }

  /**
   * Default implementation returns false (node-based instrumentation).
   * Subclasses should override if they use edge-based instrumentation.
   */
  @Override
  public boolean isEdgeBased() {
    return false;
  }
}
