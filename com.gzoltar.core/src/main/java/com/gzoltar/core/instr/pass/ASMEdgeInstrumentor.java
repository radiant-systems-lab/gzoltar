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

import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import com.gzoltar.core.instr.InstrumentationConstants;
import com.gzoltar.core.instr.granularity.PRSAlgorithm;
import com.gzoltar.core.model.EdgeAnnotation;
import com.gzoltar.core.model.EdgeAnnotationRegistry;
import com.gzoltar.core.model.Node;
import com.gzoltar.core.model.NodeType;
import com.gzoltar.core.runtime.Collector;
import com.gzoltar.core.runtime.Probe;
import com.gzoltar.core.runtime.ProbeGroup;
import com.gzoltar.core.util.MD5;

/**
 * ASM-based edge instrumentor for PRS edge coverage.
 *
 * This class uses ASM to insert probes at CFG edges rather than basic blocks.
 * It integrates with GZoltar's existing probe collection mechanism.
 *
 * Key features:
 * - Uses PRS (Probe Reduction Strategy) algorithm to identify removable nodes
 * - Inserts probes at branch points (before GOTO, after conditional jumps)
 * - Records edge metadata (removedNodes, coveredLines) for node ranking recovery
 * - Uses GZoltar's $gzoltarData field for probe storage
 */
public class ASMEdgeInstrumentor {

  /** Edge annotation records for coverage recovery */
  private final List<EdgeRecord> allEdgeRecords = new ArrayList<>();

  /** All unique node names encountered during instrumentation */
  private final Set<String> allNodes = new TreeSet<>();

  /**
   * Edge record for coverage recovery.
   */
  public static class EdgeRecord {
    public final int probeId;
    public final String methodKey;
    public final String fromNode;
    public final String toNode;
    public final List<String> removedNodes;
    public final List<String> coveredLines;

    public EdgeRecord(int probeId, String methodKey, String fromNode, String toNode,
                     List<String> removedNodes, List<String> coveredLines) {
      this.probeId = probeId;
      this.methodKey = methodKey;
      this.fromNode = fromNode;
      this.toNode = toNode;
      this.removedNodes = removedNodes != null ? removedNodes : new ArrayList<>();
      this.coveredLines = coveredLines != null ? coveredLines : new ArrayList<>();
    }

    @Override
    public String toString() {
      return "Edge#" + probeId + ": " + fromNode + " -> " + toNode +
             (removedNodes.isEmpty() ? "" : " (removed: " + removedNodes + ")");
    }
  }

  /**
   * Basic block representation for CFG analysis.
   */
  private static class BasicBlock {
    final int id;
    final int startIdx;
    int endIdx;
    int lineNumber = -1;
    final Set<Integer> allLineNumbers = new HashSet<>();
    final Set<Integer> successors = new HashSet<>();
    final Set<Integer> predecessors = new HashSet<>();

    BasicBlock(int id, int startIdx) {
      this.id = id;
      this.startIdx = startIdx;
      this.endIdx = startIdx;
    }
  }

  /**
   * Instrument a class with edge probes.
   *
   * @param classBytes Original class bytecode
   * @param probeGroup The probe group to register probes
   * @return Instrumented bytecode
   */
  public byte[] instrument(byte[] classBytes, ProbeGroup probeGroup) {
    ClassReader cr = new ClassReader(classBytes);
    ClassNode classNode = new ClassNode();
    cr.accept(classNode, ClassReader.SKIP_FRAMES);

    String classInternalName = classNode.name;
    String className = classNode.name.replace('/', '.');
    boolean instrumented = false;

    for (MethodNode method : classNode.methods) {
      // Skip abstract, native, synthetic, and static initializer methods
      if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0) {
        continue;
      }
      if (method.name.equals("<clinit>")) {
        continue;
      }

      boolean methodInstrumented = instrumentMethod(classInternalName, className, method, probeGroup);
      instrumented = instrumented || methodInstrumented;
    }

    if (!instrumented) {
      return classBytes;
    }

    // Write instrumented class
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
    classNode.accept(cw);
    return cw.toByteArray();
  }

  /**
   * Instrument a method with edge probes.
   */
  private boolean instrumentMethod(String classInternalName, String className,
                                   MethodNode method, ProbeGroup probeGroup) {
    String methodKey = className.replace('.', '$') + "#" + method.name +
                      "(" + getParamTypes(method.desc) + ")";

    InsnList insns = method.instructions;
    if (insns.size() == 0) return false;

    // Step 1: Build basic blocks
    List<BasicBlock> blocks = buildBasicBlocks(insns);
    if (blocks.isEmpty()) return false;

    // Step 2: Build CFG edges
    buildCFGEdges(blocks, insns);

    // Step 3: Apply PRS algorithm
    Set<Integer> removableBlocks = findRemovableBlocks(blocks);

    // Step 4: Build PRS edges and collect probe info
    List<EdgeInfo> edgeInfoList = buildPRSEdges(blocks, removableBlocks, methodKey);
    if (edgeInfoList.isEmpty()) return false;

    // Step 5: Register probes and insert bytecode
    int startProbeIndex = probeGroup.getProbes().size();
    for (int i = 0; i < edgeInfoList.size(); i++) {
      EdgeInfo edgeInfo = edgeInfoList.get(i);
      int probeId = startProbeIndex + i;

      // Create node for this edge
      Node node = new Node(edgeInfo.edgeName, edgeInfo.lineNumber, true, NodeType.LINE);
      Probe probe = probeGroup.registerProbe(node, null);

      // Store edge record for coverage recovery
      EdgeRecord record = new EdgeRecord(
          probeId,
          methodKey,
          edgeInfo.fromNode,
          edgeInfo.toNode,
          edgeInfo.removedNodes,
          edgeInfo.coveredLines
      );
      allEdgeRecords.add(record);

      // Track all nodes
      allNodes.addAll(edgeInfo.coveredLines);

      // Register edge annotation for node coverage recovery
      List<Integer> coveredLineNumbers = new ArrayList<>();
      for (String coveredLine : edgeInfo.coveredLines) {
        int colonIdx = coveredLine.lastIndexOf(':');
        if (colonIdx >= 0) {
          try {
            coveredLineNumbers.add(Integer.parseInt(coveredLine.substring(colonIdx + 1)));
          } catch (NumberFormatException e) {
            // ignore
          }
        }
      }

      EdgeAnnotation annotation = new EdgeAnnotation(
          i,  // edgeId
          probeId,
          methodKey,
          edgeInfo.fromNode,
          edgeInfo.toNode,
          new ArrayList<>(edgeInfo.removedNodes),
          coveredLineNumbers
      );
      EdgeAnnotationRegistry.getInstance().register(annotation);
    }

    // Step 6: Insert probes at edge locations
    insertProbes(insns, blocks, edgeInfoList, classInternalName, startProbeIndex);

    return true;
  }

  /**
   * Edge info collected during PRS edge building.
   */
  private static class EdgeInfo {
    final String edgeName;       // Full edge name with #EDGE: suffix
    final String fromNode;       // Source node
    final String toNode;         // Target node
    final int lineNumber;        // Line number for probe placement
    final int srcBlockId;        // Source block ID
    final int targetBlockId;     // Target block ID (first non-removable after srcBlockId's successor)
    final int immediateSuccId;   // Immediate successor block ID from srcBlock
    final List<String> removedNodes;
    final List<String> coveredLines;

    EdgeInfo(String edgeName, String fromNode, String toNode, int lineNumber,
            int srcBlockId, int targetBlockId, int immediateSuccId,
            List<String> removedNodes, List<String> coveredLines) {
      this.edgeName = edgeName;
      this.fromNode = fromNode;
      this.toNode = toNode;
      this.lineNumber = lineNumber;
      this.srcBlockId = srcBlockId;
      this.targetBlockId = targetBlockId;
      this.immediateSuccId = immediateSuccId;
      this.removedNodes = removedNodes;
      this.coveredLines = coveredLines;
    }
  }

  /**
   * Build basic blocks from instruction list.
   */
  private List<BasicBlock> buildBasicBlocks(InsnList insns) {
    Set<Integer> leaders = new TreeSet<>();
    leaders.add(0);

    Map<LabelNode, Integer> labelToIdx = new HashMap<>();
    for (int i = 0; i < insns.size(); i++) {
      if (insns.get(i) instanceof LabelNode) {
        labelToIdx.put((LabelNode) insns.get(i), i);
      }
    }

    for (int i = 0; i < insns.size(); i++) {
      AbstractInsnNode insn = insns.get(i);

      if (insn.getType() == AbstractInsnNode.LABEL ||
          insn.getType() == AbstractInsnNode.LINE ||
          insn.getType() == AbstractInsnNode.FRAME) {
        continue;
      }

      if (insn instanceof JumpInsnNode) {
        JumpInsnNode jump = (JumpInsnNode) insn;
        Integer targetIdx = labelToIdx.get(jump.label);
        if (targetIdx != null) {
          leaders.add(targetIdx);
        }
        if (insn.getOpcode() != Opcodes.GOTO && i + 1 < insns.size()) {
          leaders.add(i + 1);
        }
      } else if (insn instanceof TableSwitchInsnNode) {
        TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
        Integer defIdx = labelToIdx.get(sw.dflt);
        if (defIdx != null) leaders.add(defIdx);
        for (LabelNode label : sw.labels) {
          Integer idx = labelToIdx.get(label);
          if (idx != null) leaders.add(idx);
        }
      } else if (insn instanceof LookupSwitchInsnNode) {
        LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
        Integer defIdx = labelToIdx.get(sw.dflt);
        if (defIdx != null) leaders.add(defIdx);
        for (LabelNode label : sw.labels) {
          Integer idx = labelToIdx.get(label);
          if (idx != null) leaders.add(idx);
        }
      } else if (isReturnOrThrow(insn.getOpcode()) && i + 1 < insns.size()) {
        leaders.add(i + 1);
      }
    }

    List<Integer> sortedLeaders = new ArrayList<>(leaders);
    List<BasicBlock> blocks = new ArrayList<>();

    for (int i = 0; i < sortedLeaders.size(); i++) {
      int startIdx = sortedLeaders.get(i);
      int endIdx = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) - 1 : insns.size() - 1;

      BasicBlock block = new BasicBlock(i, startIdx);
      block.endIdx = endIdx;

      for (int j = startIdx; j <= endIdx && j < insns.size(); j++) {
        AbstractInsnNode insn = insns.get(j);
        if (insn instanceof LineNumberNode) {
          int line = ((LineNumberNode) insn).line;
          block.allLineNumbers.add(line);
          if (block.lineNumber < 0) {
            block.lineNumber = line;
          }
        }
      }

      blocks.add(block);
    }

    return blocks;
  }

  /**
   * Build CFG edges between basic blocks.
   */
  private void buildCFGEdges(List<BasicBlock> blocks, InsnList insns) {
    Map<LabelNode, Integer> labelToIdx = new HashMap<>();
    for (int i = 0; i < insns.size(); i++) {
      if (insns.get(i) instanceof LabelNode) {
        labelToIdx.put((LabelNode) insns.get(i), i);
      }
    }

    Map<Integer, Integer> idxToBlock = new HashMap<>();
    for (BasicBlock block : blocks) {
      for (int i = block.startIdx; i <= block.endIdx; i++) {
        idxToBlock.put(i, block.id);
      }
    }

    for (BasicBlock block : blocks) {
      AbstractInsnNode lastInsn = findLastExecutable(insns, block);

      if (lastInsn == null) {
        if (block.id + 1 < blocks.size()) {
          addEdge(block, blocks.get(block.id + 1));
        }
        continue;
      }

      if (lastInsn instanceof JumpInsnNode) {
        JumpInsnNode jump = (JumpInsnNode) lastInsn;
        Integer targetIdx = labelToIdx.get(jump.label);
        if (targetIdx != null) {
          Integer targetBlock = idxToBlock.get(targetIdx);
          if (targetBlock != null) {
            addEdge(block, blocks.get(targetBlock));
          }
        }
        if (lastInsn.getOpcode() != Opcodes.GOTO && block.id + 1 < blocks.size()) {
          addEdge(block, blocks.get(block.id + 1));
        }
      } else if (lastInsn instanceof TableSwitchInsnNode) {
        TableSwitchInsnNode sw = (TableSwitchInsnNode) lastInsn;
        addSwitchEdges(block, blocks, sw.dflt, sw.labels, labelToIdx, idxToBlock);
      } else if (lastInsn instanceof LookupSwitchInsnNode) {
        LookupSwitchInsnNode sw = (LookupSwitchInsnNode) lastInsn;
        addSwitchEdges(block, blocks, sw.dflt, sw.labels, labelToIdx, idxToBlock);
      } else if (!isReturnOrThrow(lastInsn.getOpcode())) {
        if (block.id + 1 < blocks.size()) {
          addEdge(block, blocks.get(block.id + 1));
        }
      }
    }
  }

  private void addEdge(BasicBlock from, BasicBlock to) {
    from.successors.add(to.id);
    to.predecessors.add(from.id);
  }

  private void addSwitchEdges(BasicBlock block, List<BasicBlock> blocks, LabelNode dflt,
                              List<LabelNode> labels, Map<LabelNode, Integer> labelToIdx,
                              Map<Integer, Integer> idxToBlock) {
    Integer defIdx = labelToIdx.get(dflt);
    if (defIdx != null) {
      Integer defBlock = idxToBlock.get(defIdx);
      if (defBlock != null) addEdge(block, blocks.get(defBlock));
    }
    for (LabelNode label : labels) {
      Integer idx = labelToIdx.get(label);
      if (idx != null) {
        Integer targetBlock = idxToBlock.get(idx);
        if (targetBlock != null) addEdge(block, blocks.get(targetBlock));
      }
    }
  }

  /**
   * Apply PRS algorithm to find removable blocks.
   */
  private Set<Integer> findRemovableBlocks(List<BasicBlock> blocks) {
    List<PRSAlgorithm.Block> prsBlocks = new ArrayList<>();
    for (BasicBlock block : blocks) {
      prsBlocks.add(new PRSAlgorithm.Block(
          block.id,
          block.predecessors,
          block.successors
      ));
    }

    PRSAlgorithm.PRSResult result = PRSAlgorithm.findRemovableBlocks(prsBlocks);
    return result.removableBlocks;
  }

  /**
   * Build PRS edges between non-removable blocks.
   */
  private List<EdgeInfo> buildPRSEdges(List<BasicBlock> blocks, Set<Integer> removable, String methodKey) {
    List<EdgeInfo> edgeInfoList = new ArrayList<>();
    if (blocks.isEmpty()) return edgeInfoList;

    // Collect all line numbers
    Set<Integer> methodLineNumbers = new HashSet<>();
    for (BasicBlock block : blocks) {
      methodLineNumbers.addAll(block.allLineNumbers);
    }
    if (methodLineNumbers.isEmpty()) return edgeInfoList;

    int firstLine = Collections.min(methodLineNumbers);

    // Find non-removable blocks
    Set<Integer> nonRemovable = new HashSet<>();
    for (BasicBlock block : blocks) {
      if (!removable.contains(block.id)) {
        nonRemovable.add(block.id);
      }
    }

    // Find first non-removable block
    int firstNonRemovableId = findFirstNonRemovable(blocks, removable, 0);
    if (firstNonRemovableId < 0) return edgeInfoList;

    // ENTRY edge
    String entryNode = methodKey + ":ENTRY";
    List<String> entryCoveredLines = new ArrayList<>();

    int currentId = 0;
    while (currentId >= 0 && currentId < blocks.size() && removable.contains(currentId)) {
      BasicBlock block = blocks.get(currentId);
      for (int line : block.allLineNumbers) {
        entryCoveredLines.add(methodKey + ":" + line);
      }
      if (block.successors.size() == 1) {
        currentId = block.successors.iterator().next();
      } else {
        break;
      }
    }

    BasicBlock firstNonRemBlock = blocks.get(firstNonRemovableId);
    String firstNonRemNode = firstNonRemBlock.lineNumber > 0 ?
        methodKey + ":" + firstNonRemBlock.lineNumber :
        methodKey + ":BLOCK" + firstNonRemovableId;

    for (int line : firstNonRemBlock.allLineNumbers) {
      String lineName = methodKey + ":" + line;
      if (!entryCoveredLines.contains(lineName)) {
        entryCoveredLines.add(lineName);
      }
    }

    String entryEdgeLabel = "ENTRY->" + (firstNonRemBlock.lineNumber > 0 ? firstNonRemBlock.lineNumber : "BLOCK" + firstNonRemovableId);
    String entryEdgeName = methodKey + ":" + firstLine + "#EDGE:" + entryEdgeLabel;

    edgeInfoList.add(new EdgeInfo(
        entryEdgeName, entryNode, firstNonRemNode, firstLine,
        -1, firstNonRemovableId, firstNonRemovableId,
        new ArrayList<>(), entryCoveredLines
    ));

    // Create edges from each non-removable block with multiple successors
    for (int srcId : nonRemovable) {
      BasicBlock srcBlock = blocks.get(srcId);

      if (srcBlock.successors.size() <= 1) {
        continue;
      }

      String srcNode = srcBlock.lineNumber > 0 ?
          methodKey + ":" + srcBlock.lineNumber :
          methodKey + ":BLOCK" + srcId;

      for (int succId : srcBlock.successors) {
        List<String> removedNodes = new ArrayList<>();
        List<String> coveredLines = new ArrayList<>();

        if (srcBlock.lineNumber > 0) {
          coveredLines.add(srcNode);
        }

        // Track through removable blocks
        int trackId = succId;
        while (trackId >= 0 && trackId < blocks.size() && removable.contains(trackId)) {
          BasicBlock trackBlock = blocks.get(trackId);
          if (trackBlock.lineNumber > 0) {
            removedNodes.add(methodKey + ":" + trackBlock.lineNumber);
          }
          for (int line : trackBlock.allLineNumbers) {
            String lineName = methodKey + ":" + line;
            if (!coveredLines.contains(lineName)) {
              coveredLines.add(lineName);
            }
          }
          if (trackBlock.successors.size() == 1) {
            trackId = trackBlock.successors.iterator().next();
          } else {
            break;
          }
        }

        int targetId = findFirstNonRemovable(blocks, removable, succId);
        if (targetId >= 0 && targetId < blocks.size()) {
          BasicBlock dstBlock = blocks.get(targetId);
          for (int line : dstBlock.allLineNumbers) {
            String lineName = methodKey + ":" + line;
            if (!coveredLines.contains(lineName)) {
              coveredLines.add(lineName);
            }
          }

          String dstNode = dstBlock.lineNumber > 0 ?
              methodKey + ":" + dstBlock.lineNumber :
              methodKey + ":BLOCK" + targetId;

          int probeLine = dstBlock.lineNumber > 0 ? dstBlock.lineNumber : srcBlock.lineNumber;
          String srcLabel = srcBlock.lineNumber > 0 ? String.valueOf(srcBlock.lineNumber) : "BLOCK" + srcId;
          String dstLabel = dstBlock.lineNumber > 0 ? String.valueOf(dstBlock.lineNumber) : "BLOCK" + targetId;
          String edgeLabel = srcLabel + "->" + dstLabel;
          String edgeName = methodKey + ":" + probeLine + "#EDGE:" + edgeLabel;

          edgeInfoList.add(new EdgeInfo(
              edgeName, srcNode, dstNode, probeLine,
              srcId, targetId, succId,
              removedNodes, coveredLines
          ));
        }
      }
    }

    return edgeInfoList;
  }

  private int findFirstNonRemovable(List<BasicBlock> blocks, Set<Integer> removable, int startId) {
    if (startId < 0 || startId >= blocks.size()) {
      return -1;
    }
    if (!removable.contains(startId)) {
      return startId;
    }

    int currentId = startId;
    while (currentId >= 0 && currentId < blocks.size() && removable.contains(currentId)) {
      BasicBlock block = blocks.get(currentId);
      if (block.successors.size() == 1) {
        currentId = block.successors.iterator().next();
      } else if (block.successors.size() > 1) {
        return currentId;
      } else {
        return -1;
      }
    }
    return currentId;
  }

  /**
   * Insert probes at edge locations.
   */
  private void insertProbes(InsnList insns, List<BasicBlock> blocks, List<EdgeInfo> edgeInfoList,
                           String classInternalName, int startProbeIndex) {
    // Build mappings
    Map<Integer, BasicBlock> lineToBlock = new HashMap<>();
    for (BasicBlock block : blocks) {
      if (block.lineNumber > 0) {
        lineToBlock.put(block.lineNumber, block);
      }
    }

    Map<LabelNode, Integer> labelToBlockId = new HashMap<>();
    for (BasicBlock block : blocks) {
      for (int i = block.startIdx; i <= block.endIdx && i < insns.size(); i++) {
        AbstractInsnNode node = insns.get(i);
        if (node instanceof LabelNode) {
          labelToBlockId.put((LabelNode) node, block.id);
        }
      }
    }

    // Process each edge
    for (int i = 0; i < edgeInfoList.size(); i++) {
      EdgeInfo edge = edgeInfoList.get(i);
      int probeId = startProbeIndex + i;

      if (edge.srcBlockId < 0) {
        // ENTRY edge - insert at method start
        AbstractInsnNode firstExec = findFirstExecutable(insns);
        if (firstExec != null) {
          insns.insertBefore(firstExec, createProbeCode(classInternalName, probeId));
        }
        continue;
      }

      BasicBlock srcBlock = blocks.get(edge.srcBlockId);
      AbstractInsnNode lastInsn = findLastExecutable(insns, srcBlock);
      if (lastInsn == null) continue;

      if (lastInsn instanceof JumpInsnNode) {
        JumpInsnNode jump = (JumpInsnNode) lastInsn;

        if (jump.getOpcode() == Opcodes.GOTO) {
          // Insert before GOTO
          insns.insertBefore(jump, createProbeCode(classInternalName, probeId));
        } else {
          // Conditional jump
          Integer jumpTargetBlockId = labelToBlockId.get(jump.label);

          if (jumpTargetBlockId != null && jumpTargetBlockId.equals(edge.immediateSuccId)) {
            // This edge is for the jump-taken path
            LabelNode probeLabel = new LabelNode();
            InsnList probeBlock = new InsnList();
            probeBlock.add(probeLabel);
            probeBlock.add(createProbeCode(classInternalName, probeId));
            probeBlock.add(new JumpInsnNode(Opcodes.GOTO, jump.label));

            insns.insertBefore(jump.label, probeBlock);
            jump.label = probeLabel;
          } else if (edge.srcBlockId + 1 < blocks.size() &&
                     blocks.get(edge.srcBlockId + 1).id == edge.immediateSuccId) {
            // This edge is for the fall-through path
            insns.insert(jump, createProbeCode(classInternalName, probeId));
          }
        }
      }
    }
  }

  /**
   * Create probe bytecode: $gzoltarData[probeId] = true
   */
  private InsnList createProbeCode(String classInternalName, int probeId) {
    InsnList code = new InsnList();

    // GETSTATIC className.$gzoltarData : [Z
    code.add(new FieldInsnNode(Opcodes.GETSTATIC,
        classInternalName,
        InstrumentationConstants.FIELD_NAME,
        InstrumentationConstants.FIELD_DESC_BYTECODE));

    // SIPUSH probeId
    code.add(new IntInsnNode(Opcodes.SIPUSH, probeId & 0xFFFF));

    // ICONST_1
    code.add(new InsnNode(Opcodes.ICONST_1));

    // BASTORE
    code.add(new InsnNode(Opcodes.BASTORE));

    return code;
  }

  private AbstractInsnNode findLastExecutable(InsnList insns, BasicBlock block) {
    for (int j = block.endIdx; j >= block.startIdx; j--) {
      AbstractInsnNode insn = insns.get(j);
      if (insn.getType() != AbstractInsnNode.LABEL &&
          insn.getType() != AbstractInsnNode.LINE &&
          insn.getType() != AbstractInsnNode.FRAME) {
        return insn;
      }
    }
    return null;
  }

  private AbstractInsnNode findFirstExecutable(InsnList insns) {
    for (int i = 0; i < insns.size(); i++) {
      AbstractInsnNode insn = insns.get(i);
      if (insn.getType() != AbstractInsnNode.LABEL &&
          insn.getType() != AbstractInsnNode.LINE &&
          insn.getType() != AbstractInsnNode.FRAME) {
        return insn;
      }
    }
    return null;
  }

  private boolean isReturnOrThrow(int opcode) {
    return opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN ||
           opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN ||
           opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN ||
           opcode == Opcodes.ATHROW;
  }

  private String getParamTypes(String desc) {
    StringBuilder sb = new StringBuilder();
    int i = 1;
    while (i < desc.length() && desc.charAt(i) != ')') {
      if (sb.length() > 0) sb.append(",");
      char c = desc.charAt(i);
      switch (c) {
        case 'Z': sb.append("boolean"); i++; break;
        case 'B': sb.append("byte"); i++; break;
        case 'C': sb.append("char"); i++; break;
        case 'S': sb.append("short"); i++; break;
        case 'I': sb.append("int"); i++; break;
        case 'J': sb.append("long"); i++; break;
        case 'F': sb.append("float"); i++; break;
        case 'D': sb.append("double"); i++; break;
        case 'L':
          int end = desc.indexOf(';', i);
          if (end < 0) { i++; break; }
          String clsName = desc.substring(i + 1, end).replace('/', '.');
          sb.append(clsName.substring(clsName.lastIndexOf('.') + 1));
          i = end + 1;
          break;
        case '[':
          int dims = 0;
          while (i < desc.length() && desc.charAt(i) == '[') { dims++; i++; }
          if (i >= desc.length()) break;
          if (desc.charAt(i) == 'L') {
            int arrEnd = desc.indexOf(';', i);
            if (arrEnd < 0) { i++; break; }
            String arrType = desc.substring(i + 1, arrEnd).replace('/', '.');
            sb.append(arrType.substring(arrType.lastIndexOf('.') + 1));
            i = arrEnd + 1;
          } else {
            switch (desc.charAt(i)) {
              case 'Z': sb.append("boolean"); break;
              case 'B': sb.append("byte"); break;
              case 'C': sb.append("char"); break;
              case 'S': sb.append("short"); break;
              case 'I': sb.append("int"); break;
              case 'J': sb.append("long"); break;
              case 'F': sb.append("float"); break;
              case 'D': sb.append("double"); break;
            }
            i++;
          }
          for (int d = 0; d < dims; d++) sb.append("[]");
          break;
        default: i++;
      }
    }
    return sb.toString();
  }

  /**
   * Get all edge records for coverage recovery.
   */
  public List<EdgeRecord> getAllEdgeRecords() {
    return new ArrayList<>(allEdgeRecords);
  }

  /**
   * Get all unique node names.
   */
  public Set<String> getAllNodes() {
    return new TreeSet<>(allNodes);
  }
}
