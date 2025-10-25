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
package com.gzoltar.core.instr.pass;

import java.util.ArrayList;
import java.util.List;
import com.gzoltar.core.AgentConfigs;
import com.gzoltar.core.instr.InstrumentationConstants;
import com.gzoltar.core.instr.InstrumentationLevel;
import com.gzoltar.core.instr.Outcome;
import com.gzoltar.core.instr.filter.AnonymousClassConstructorFilter;
import com.gzoltar.core.instr.filter.EmptyMethodFilter;
import com.gzoltar.core.instr.filter.EnumFilter;
import com.gzoltar.core.instr.filter.IFilter;
import com.gzoltar.core.instr.filter.SyntheticFilter;
import com.gzoltar.core.instr.filter.Java7InterfaceFilter;
import com.gzoltar.core.instr.granularity.GranularityFactory;
import com.gzoltar.core.instr.granularity.GranularityLevel;
import com.gzoltar.core.instr.granularity.IGranularity;
import com.gzoltar.core.model.Node;
import com.gzoltar.core.model.NodeFactory;
import com.gzoltar.core.runtime.Collector;
import com.gzoltar.core.runtime.Probe;
import com.gzoltar.core.runtime.ProbeGroup;
import com.gzoltar.core.util.MD5;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.bytecode.Bytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;

public class CoveragePass implements IPass {

  // ThreadLocal to track the last hit node ID for edge-based coverage
  public static final ThreadLocal<Integer> __gz_lastHitNodeId = new ThreadLocal<>();

  // Method to reset the ThreadLocal (called at test start)
  public static void resetLastHitNode() {
    __gz_lastHitNodeId.set(null);
  }

  private final InstrumentationLevel instrumentationLevel;

  private final GranularityLevel granularityLevel;

  private final FieldPass fieldPass = new FieldPass();

  private AbstractInitMethodPass initMethodPass = null;

  private final StackSizePass stackSizePass = new StackSizePass();

  private final List<IFilter> filtersAtClassLevel = new ArrayList<IFilter>();

  private final List<IFilter> filtersAtMethodLevel = new ArrayList<IFilter>();

  private ProbeGroup probeGroup;

  public CoveragePass(final AgentConfigs agentConfigs) {

    this.instrumentationLevel = agentConfigs.getInstrumentationLevel();
    this.granularityLevel = agentConfigs.getGranularity();

    switch (this.instrumentationLevel) {
      case FULL:
      default:
        this.initMethodPass = new InitMethodPass();
        break;
      case OFFLINE:
        this.initMethodPass = new OfflineInitMethodPass();
        break;
      case NONE:
        break;
    }

    // exclude synthetic methods
    this.filtersAtMethodLevel.add(new SyntheticFilter());

    // exclude methods 'values' and 'valuesOf' of enum classes
    this.filtersAtMethodLevel.add(new EnumFilter());

    // exclude methods without any source code
    this.filtersAtMethodLevel.add(new EmptyMethodFilter());

    // exclude constructor of an Anonymous class as the same line number is handled by the
    // superclass
    this.filtersAtMethodLevel.add(new AnonymousClassConstructorFilter());

    // exclude Java-7 interfaces from being instrumented
    this.filtersAtClassLevel.add(new Java7InterfaceFilter());
  }

  @Override
  public synchronized Outcome transform(final CtClass ctClass) throws Exception {
    boolean instrumented = false;

    // check whether this class could/should be instrumented
    for (IFilter filter : this.filtersAtClassLevel) {
      switch (filter.filter(ctClass)) {
        case REJECT:
          return Outcome.REJECT;
        case ACCEPT:
        default:
          continue;
      }
    }

    byte[] originalBytes = ctClass.toBytecode(); // toBytecode() method frozens the class
    // in order to be able to modify it, it has to be defrosted
    ctClass.defrost();

    String hash = MD5.calculateHash(originalBytes);
    this.probeGroup = new ProbeGroup(hash, ctClass);

    for (CtBehavior ctBehavior : ctClass.getDeclaredBehaviors()) {
      boolean behaviorInstrumented = !this.transform(ctClass, ctBehavior).equals(Outcome.REJECT);
      instrumented = instrumented || behaviorInstrumented;

      if (behaviorInstrumented) {
        // update stack size
        this.stackSizePass.transform(ctClass, ctBehavior);
      }
    }

    // register class' probes
    Collector.instance().regiterProbeGroup(this.probeGroup);

    if (instrumented && this.initMethodPass != null) {
      // insert data field
      this.fieldPass.transform(ctClass);

      // make method to init GZoltar's field
      this.initMethodPass.setHash(hash);
      this.initMethodPass.transform(ctClass);

      // make sure GZoltar's field is initialised. note: the following code requires the init method
      // to be in the instrumented class, otherwise a compilation error is thrown

      boolean hasAnyStaticInitializerBeenInstrumented = false;
      for (CtBehavior ctBehavior : ctClass.getDeclaredBehaviors()) {
        if (ctBehavior.getName().equals(InstrumentationConstants.INIT_METHOD_NAME)) {
          // for obvious reasons, init method cannot call itself
          continue;
        }

        // before executing the code of every single method, check whether FIELD_NAME has been
        // initialised. if not, init method should initialise the field
        this.initMethodPass.transform(ctClass, ctBehavior);

        if (hasAnyStaticInitializerBeenInstrumented == false
            && ctBehavior.getMethodInfo2().isStaticInitializer()) {
          hasAnyStaticInitializerBeenInstrumented = true;
        }
      }

      if (!hasAnyStaticInitializerBeenInstrumented) {
        CtConstructor clinit = ctClass.makeClassInitializer();
        this.initMethodPass.transform(ctClass, clinit);
      }

      // Allow granularity to add custom fields and initialization
      // This is used by edge-based granularities (e.g., SELECTIVE_CFG) to add lookup tables
      try {
        // First, collect all edge-to-probe mappings from all methods
        IGranularity sampleGranularity = GranularityFactory.getGranularity(ctClass, null, this.granularityLevel);

        // Add custom fields (e.g., edge lookup table for SELECTIVE_CFG)
        sampleGranularity.addCustomFields(ctClass);

        // Initialize custom fields in <clinit> if needed
        if (sampleGranularity.isEdgeBased()) {
          // For edge-based granularities, we need to pass the edge-to-probe mapping
          java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> edgeMapping =
              this.classEdgeMappings.get(ctClass);
          sampleGranularity.initializeCustomFields(ctClass, edgeMapping);
        } else {
          sampleGranularity.initializeCustomFields(ctClass, null);
        }
      } catch (Exception e) {
        System.err.println("Error adding custom fields/initialization: " + e.getMessage());
        e.printStackTrace();
      }
    }

    return Outcome.ACCEPT;
  }

  @Override
  public Outcome transform(final CtClass ctClass, final CtBehavior ctBehavior) throws Exception {
    Outcome instrumented = Outcome.REJECT;

    // check whether this method should be instrumented
    for (IFilter filter : this.filtersAtMethodLevel) {
      switch (filter.filter(ctBehavior)) {
        case REJECT:
          return instrumented;
        case ACCEPT:
        default:
          continue;
      }
    }

    boolean injectBytecode = this.instrumentationLevel == InstrumentationLevel.FULL
        || this.instrumentationLevel == InstrumentationLevel.OFFLINE;

    MethodInfo methodInfo = ctBehavior.getMethodInfo();
    CodeAttribute ca = methodInfo.getCodeAttribute();

    if (ca == null) {
      return Outcome.REJECT;
    }

    // create granularity instance for this method
    IGranularity granularity = GranularityFactory.getGranularity(ctClass, methodInfo, this.granularityLevel);

    // Handle edge-based granularities differently (they create probes for edges, not nodes)
    if (granularity.isEdgeBased()) {
      return transformEdgeBased(ctClass, ctBehavior, granularity, methodInfo, ca, injectBytecode);
    }

    // Standard node-based instrumentation
    CodeIterator ci = ca.iterator();
    int index = 0, curLine = -1, instrSize = 0;

    while (ci.hasNext()) {
      index = ci.next();
      curLine = methodInfo.getLineNumber(index);

      if (curLine == -1) {
        continue;
      }

      // Check if we should instrument at this index based on granularity
      boolean shouldInstrument = granularity.instrumentAtIndex(index, instrSize);

      if (shouldInstrument) {
        // create a node for this instrumentation point
        Node node = NodeFactory.createNode(ctClass, ctBehavior, curLine, shouldInstrument);
        assert node != null;

        // add granularity-specific suffix if needed
        String suffix = granularity.getNodeSuffix();
        if (!suffix.isEmpty()) {
          node.setName(node.getName() + suffix);
        }

        // Always register probe and instrument ALL blocks (including #SKIP)
        // This ensures coverage data is identical to BASICBLOCK
        // Blocks marked #SKIP can be filtered out in post-processing if needed
        Probe probe = this.probeGroup.registerProbe(node, ctBehavior);
        assert probe != null;

        if (injectBytecode) {
          Bytecode bc = this.getInstrumentationCode(ctClass, probe, methodInfo.getConstPool());
          ci.insert(index, bc.get());
          instrSize += bc.length();
          instrumented = Outcome.ACCEPT;
        } else {
          instrumented = Outcome.REJECT;
        }
      }

      // check if we should stop instrumenting (granularity-specific logic)
      if (granularity.stopInstrumenting()) {
        break;
      }
    }

    return instrumented;
  }

  /**
   * Transformer for edge-based granularities (e.g., SELECTIVE_CFG).
   * Creates nodes for edges instead of basic blocks, and uses granularity-specific
   * instrumentation code.
   */
  private Outcome transformEdgeBased(final CtClass ctClass, final CtBehavior ctBehavior,
      IGranularity granularity, MethodInfo methodInfo, CodeAttribute ca, boolean injectBytecode) throws Exception {

    Outcome instrumented = Outcome.REJECT;

    // Get edge information from granularity
    java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, String> edgeLabels = granularity.getEdgeLabels();

    if (edgeLabels.isEmpty()) {
      return Outcome.REJECT;
    }

    // Create edge-to-probe mapping
    java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> edgeToProbeIndex =
        new java.util.HashMap<>();

    // Create one node for each edge with the edge label as the line identifier
    for (java.util.Map.Entry<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, String> entry : edgeLabels.entrySet()) {
      org.apache.commons.lang3.tuple.Pair<Integer, Integer> edge = entry.getKey();
      String edgeLabel = entry.getValue();

      // Create node with edge label instead of line number
      Node node = NodeFactory.createNode(ctClass, ctBehavior, -1, true);
      // Replace the line number part with the edge label
      String nodeName = node.getName();
      int lastColonIndex = nodeName.lastIndexOf(':');
      if (lastColonIndex > 0) {
        nodeName = nodeName.substring(0, lastColonIndex + 1) + edgeLabel;
        node.setName(nodeName);
      }

      // Register the node and store the probe index
      Probe probe = this.probeGroup.registerProbe(node, ctBehavior);
      assert probe != null;
      edgeToProbeIndex.put(edge, probe.getArrayIndex());
    }

    // Store edge mapping for later use in custom field initialization
    storeEdgeMappingForClass(ctClass, edgeToProbeIndex);

    // Get offset-to-blockId mapping from granularity (SelectiveCFG-specific)
    java.util.Map<Integer, Integer> offsetToBlockId = new java.util.HashMap<>();
    if (granularity instanceof com.gzoltar.core.instr.granularity.SelectiveCFGGranularity) {
      com.gzoltar.core.instr.granularity.SelectiveCFGGranularity selectiveGranularity =
          (com.gzoltar.core.instr.granularity.SelectiveCFGGranularity) granularity;
      offsetToBlockId = selectiveGranularity.getOffsetToBlockIdMap();
    }

    // Now instrument the selected basic blocks with edge tracking code
    CodeIterator ci = ca.iterator();
    int index = 0, curLine = -1, instrSize = 0;
    int maxLocals = ca.getMaxLocals();

    while (ci.hasNext()) {
      index = ci.next();
      curLine = methodInfo.getLineNumber(index);

      if (curLine == -1) {
        continue;
      }

      // Check if we should instrument at this index based on granularity
      boolean shouldInstrument = granularity.instrumentAtIndex(index, instrSize);

      if (shouldInstrument && injectBytecode) {
        // Get the block ID for the offset that was chosen for instrumentation
        Integer blockOffset = null;
        if (granularity instanceof com.gzoltar.core.instr.granularity.SelectiveCFGGranularity) {
          com.gzoltar.core.instr.granularity.SelectiveCFGGranularity selectiveGranularity =
              (com.gzoltar.core.instr.granularity.SelectiveCFGGranularity) granularity;
          blockOffset = selectiveGranularity.getLastInstrumentedOffset();
        }

        Integer blockId = blockOffset != null ? offsetToBlockId.get(blockOffset) : null;

        if (blockId != null) {
          // Use granularity's custom instrumentation code
          java.util.Map<String, Object> context = new java.util.HashMap<>();
          context.put("blockId", blockId);
          context.put("maxLocals", maxLocals);

          Bytecode bc = granularity.generateCustomInstrumentationCode(
              ctClass, methodInfo.getConstPool(), context);

          if (bc != null) {
            ci.insert(index, bc.get());
            instrSize += bc.length();
            instrumented = Outcome.ACCEPT;
          }
        }
      }

      // check if we should stop instrumenting
      if (granularity.stopInstrumenting()) {
        break;
      }
    }

    return instrumented;
  }

  // Store edge mapping per class for <clinit> generation
  private java.util.Map<CtClass, java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer>>
      classEdgeMappings = new java.util.HashMap<>();

  private void storeEdgeMappingForClass(CtClass ctClass,
      java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> edgeMapping) {

    // Merge with existing mappings for this class
    java.util.Map<org.apache.commons.lang3.tuple.Pair<Integer, Integer>, Integer> existingMapping =
        classEdgeMappings.get(ctClass);

    if (existingMapping == null) {
      classEdgeMappings.put(ctClass, new java.util.HashMap<>(edgeMapping));
    } else {
      existingMapping.putAll(edgeMapping);
    }
  }


  private Bytecode getInstrumentationCode(CtClass ctClass, Probe probe, ConstPool constPool) {
    Bytecode b = new Bytecode(constPool);
    b.addGetstatic(ctClass, InstrumentationConstants.FIELD_NAME,
        InstrumentationConstants.FIELD_DESC_BYTECODE);

    // Use sipush for all indices to ensure consistent probe size (7 bytes total)
    // This is important for selective instrumentation to work correctly
    int index = probe.getArrayIndex();
    b.addOpcode(Opcode.SIPUSH);
    b.add((index >>> 8) & 0xFF);
    b.add(index & 0xFF);

    b.addOpcode(Opcode.ICONST_1);
    b.addOpcode(Opcode.BASTORE);

    return b;
  }


}