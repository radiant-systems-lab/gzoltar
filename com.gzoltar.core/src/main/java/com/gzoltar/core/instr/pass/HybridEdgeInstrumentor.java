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
import java.io.ByteArrayInputStream;
import java.io.File;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.commons.JSRInlinerAdapter;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;

import com.gzoltar.core.instr.InstrumentationConstants;
import com.gzoltar.core.instr.granularity.PRSAlgorithm;
import com.gzoltar.core.model.EdgeAnnotation;
import com.gzoltar.core.model.EdgeAnnotationRegistry;
import com.gzoltar.core.model.Node;
import com.gzoltar.core.model.NodeType;
import com.gzoltar.core.runtime.ProbeGroup;
import com.gzoltar.core.runtime.Probe;

/**
 * Hybrid Edge Instrumentor: Uses Javassist for CFG construction + PRS algorithm,
 * then ASM for precise bytecode instrumentation.
 *
 * This combines the best of both worlds:
 * - Javassist's mature CFG analysis (ControlFlow.Block)
 * - PRS algorithm to identify removable nodes
 * - ASM's precise bytecode manipulation for probe insertion
 *
 * Workflow:
 * 1. Javassist builds CFG from class bytecode
 * 2. PRS algorithm identifies which blocks can be removed
 * 3. Build PRS edges between non-removable blocks
 * 4. ASM inserts probes at the identified edge positions
 */
public class HybridEdgeInstrumentor {

  /** Hash for this class (set by Instrumenter) */
  private String currentClassHash;

  private static boolean hookRegistered = false;
  private static String agentOutputDirectory = ".";

  public static void setAgentOutputDirectory(String dir) {
    agentOutputDirectory = dir;
  }

  /** Static timing accumulators for performance measurement */
  private static long totalInstrTime = 0;
  private static int totalClassCount = 0;
  private static int verifyErrorCount = 0;

  /** Reset timing stats (call before benchmark run) */
  public static synchronized void resetTimingStats() {
    totalInstrTime = 0;
    totalClassCount = 0;
    verifyErrorCount = 0;
  }

  /** Get verify error count */
  public static synchronized int getVerifyErrorCount() {
    return verifyErrorCount;
  }

  /** Get total instrumentation time in milliseconds */
  public static synchronized long getTotalInstrTime() {
    return totalInstrTime;
  }

  /** Get total instrumented class count */
  public static synchronized int getTotalClassCount() {
    return totalClassCount;
  }

  /** Print timing summary (call after all tests complete) */
  public static synchronized void printTimingSummary() {
    System.out.println("[EDGE-TIMING] Total instrumentation time: " + totalInstrTime + "ms for " + totalClassCount + " classes (VerifyErrors: " + verifyErrorCount + ")");
  }

  /**
   * Information about an edge identified by Javassist CFG + PRS.
   * Now includes bytecode positions for both source and destination blocks.
   */
  public static class EdgeInfo {
    public final String edgeName;
    public final String fromNode;
    public final String toNode;
    public final int lineNumber;
    public final int srcBlockPosition;    // Bytecode offset of source block start (from Javassist Block.position())
    public final int srcBlockEndPosition; // Bytecode offset where source block ends (next block's start or MAX_VALUE)
    public final int dstBlockPosition;    // Bytecode offset of destination block start
    public final List<String> annotatedNodes;
    public final List<String> coveredLines;
    public final int fromBlockIndex;
    public final int toBlockIndex;
    public int probeId = -1;              // Assigned probe ID (local to class)

    public EdgeInfo(String edgeName, String fromNode, String toNode, int lineNumber,
                   int srcBlockPosition, int srcBlockEndPosition, int dstBlockPosition,
                   List<String> annotatedNodes, List<String> coveredLines,
                   int fromBlockIndex, int toBlockIndex) {
      this.edgeName = edgeName;
      this.fromNode = fromNode;
      this.toNode = toNode;
      this.lineNumber = lineNumber;
      this.srcBlockPosition = srcBlockPosition;
      this.srcBlockEndPosition = srcBlockEndPosition;
      this.dstBlockPosition = dstBlockPosition;
      this.annotatedNodes = annotatedNodes;
      this.coveredLines = coveredLines;
      this.fromBlockIndex = fromBlockIndex;
      this.toBlockIndex = toBlockIndex;
    }
  }

  // ==================== Label Offset 映射工具方法 ====================

  /**
   * 建立 字节码偏移量 -> LabelNode 的精确映射。
   * 使用 ASM Label.getOffset() 获取真实的字节码偏移量（由 ClassReader 读取）。
   * 返回 TreeMap 以支持 floorEntry() 查找最近的前驱 label。
   */
  public static TreeMap<Integer, LabelNode> buildOffsetToLabelMap(InsnList insns) {
    TreeMap<Integer, LabelNode> map = new TreeMap<>();
    for (AbstractInsnNode n = insns.getFirst(); n != null; n = n.getNext()) {
      if (n instanceof LabelNode) {
        LabelNode ln = (LabelNode) n;
        try {
          int off = ln.getLabel().getOffset();  // 关键：真实 offset（ClassReader 读出来的）
          map.put(off, ln);
        } catch (IllegalStateException e) {
          // Label offset not yet resolved - skip
        }
      }
    }
    return map;
  }

  /**
   * 查找最近的前驱 label（offset <= targetOffset 的最大 label）。
   * 当 block 起点没有恰好的 label 时，使用最近的前驱 label 作为兜底。
   */
  private static LabelNode findNearestLabel(TreeMap<Integer, LabelNode> offsetToLabel, int targetOffset) {
    // 先精确查找
    LabelNode exact = offsetToLabel.get(targetOffset);
    if (exact != null) {
      return exact;
    }
    // 找最近的前驱 label（offset <= targetOffset）
    Map.Entry<Integer, LabelNode> floor = offsetToLabel.floorEntry(targetOffset);
    return (floor != null) ? floor.getValue() : null;
  }

  /**
   * 反转条件跳转的 opcode（用于就地 trampoline）
   */
  private static int invertIfOpcode(int op) {
    switch (op) {
      case Opcodes.IFEQ: return Opcodes.IFNE;
      case Opcodes.IFNE: return Opcodes.IFEQ;
      case Opcodes.IFLT: return Opcodes.IFGE;
      case Opcodes.IFGE: return Opcodes.IFLT;
      case Opcodes.IFGT: return Opcodes.IFLE;
      case Opcodes.IFLE: return Opcodes.IFGT;
      case Opcodes.IF_ICMPEQ: return Opcodes.IF_ICMPNE;
      case Opcodes.IF_ICMPNE: return Opcodes.IF_ICMPEQ;
      case Opcodes.IF_ICMPLT: return Opcodes.IF_ICMPGE;
      case Opcodes.IF_ICMPGE: return Opcodes.IF_ICMPLT;
      case Opcodes.IF_ICMPGT: return Opcodes.IF_ICMPLE;
      case Opcodes.IF_ICMPLE: return Opcodes.IF_ICMPGT;
      case Opcodes.IF_ACMPEQ: return Opcodes.IF_ACMPNE;
      case Opcodes.IF_ACMPNE: return Opcodes.IF_ACMPEQ;
      case Opcodes.IFNULL: return Opcodes.IFNONNULL;
      case Opcodes.IFNONNULL: return Opcodes.IFNULL;
      default: return -1;  // 不是条件跳转
    }
  }

  /**
   * Set the class hash for init method creation.
   */
  public void setClassHash(String hash) {
    this.currentClassHash = hash;
  }

  private void registerShutdownHook() {
    synchronized (HybridEdgeInstrumentor.class) {
      if (hookRegistered) {
        return;
      }
      hookRegistered = true;
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        if (EdgeAnnotationRegistry.getInstance().size() > 0) {
          System.out.println("[GZoltar] Saving edge annotations to " + agentOutputDirectory + " ...");
          EdgeAnnotationRegistry.getInstance().saveAll(new File(agentOutputDirectory));
          System.out.println("[GZoltar] Edge annotations saved.");
        }
      } catch (Throwable t) {
        System.err.println("[GZoltar] Failed to save edge annotations: " + t.getMessage());
        t.printStackTrace();
      }
    }));
  }

  /**
   * Instrument a class with hybrid edge probes.
   *
   * @param classBytes Original class bytecode
   * @param probeGroup The probe group to register probes
   * @param loader The class loader that is loading this class (used for verification)
   * @return Instrumented bytecode
   */
  public byte[] instrument(byte[] classBytes, ProbeGroup probeGroup, ClassLoader loader) throws Exception {
    registerShutdownHook();
    long startTime = System.currentTimeMillis();

    // Step 1: Use Javassist to analyze CFG for all methods
    ClassPool pool = ClassPool.getDefault();
    CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classBytes));

    // Skip interfaces - they have no executable code to instrument
    if (ctClass.isInterface()) {
      return classBytes;
    }

    String className = ctClass.getName();
    String classInternalName = className.replace('.', '/');

    // Build edge information from Javassist CFG + PRS for all methods
    Map<String, List<EdgeInfo>> methodEdges = new HashMap<>();

    for (javassist.CtBehavior behavior : ctClass.getDeclaredBehaviors()) {
      try {
      // Skip abstract, native methods
      if (javassist.Modifier.isAbstract(behavior.getModifiers()) ||
          javassist.Modifier.isNative(behavior.getModifiers())) {
        continue;
      }

      MethodInfo methodInfo = behavior.getMethodInfo();
      String internalMethodName = methodInfo.getName();

      // Skip static initializer and our own init method
      if (internalMethodName.equals("<clinit>") || internalMethodName.equals(InstrumentationConstants.INIT_METHOD_NAME)) {
        continue;
      }

      // Use behavior.getName() to match BB format:
      // - For constructors: returns class simple name (e.g., "App") instead of "<init>"
      // - For methods: returns method name (e.g., "mid")
      String methodName = behavior.getName();
      // DO NOT replace '.' with '$' in className - this breaks package structure in node names!
      // BASICBLOCK uses: package.Class#method
      // We should use: package.Class#method
      String methodKey = className + "#" + methodName +
                        "(" + getParamTypes(methodInfo.getDescriptor()) + ")";

        // Use Javassist ControlFlow to build CFG
        ControlFlow cf = new ControlFlow(ctClass, methodInfo);
        Block[] blocks = cf.basicBlocks();

        if (blocks == null || blocks.length == 0) {
          continue;
        }

        // Apply PRS algorithm to identify edges
        List<EdgeInfo> edges = analyzeMethodWithPRS(blocks, methodInfo, methodKey);
        if (!edges.isEmpty()) {
          methodEdges.put(methodKey, edges);
        }
      } catch (Throwable e) {
        System.err.println("[GZoltar] Error analyzing behavior in " + className + ": " + e);
        e.printStackTrace();
      }
    }

    // DO NOT detach ctClass - it's needed in ClassPool for duplicate detection across test runs
    // ctClass.detach();

    // Step 2: Use ASM to insert probes at the identified positions
    ClassReader cr = new ClassReader(classBytes);
    ClassNode classNode = new ClassNode();

    // Use JSRInlinerAdapter to remove JSR/RET instructions (subroutines)
    // This makes bytecode Java 6+ compatible and allows COMPUTE_FRAMES to work correctly
    ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, classNode) {
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new JSRInlinerAdapter(mv, access, name, descriptor, signature, exceptions);
        }
    };

    // Use EXPAND_FRAMES to fully expand stack map frames - this helps COMPUTE_FRAMES recompute them correctly
    try {
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
    } catch (Throwable t) {
        System.err.println("[GZoltar] ASM accept failed for " + className + ": " + t);
        t.printStackTrace();
        return classBytes; // Return original bytecode
    }

    int probeCountBefore = probeGroup.getProbes().size();
    boolean instrumented = false;
    List<EdgeAnnotation> allAnnotations = new ArrayList<>();

    // Class-level edge ID counter (fixes issue: edgeId must be unique across all methods in a class)
    java.util.concurrent.atomic.AtomicInteger edgeIdCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    // Instrument each method using ASM
    for (MethodNode method : classNode.methods) {
      // Skip abstract, native, synthetic, and static initializer methods
      if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0) {
        continue;
      }
      if (method.name.equals("<clinit>") || method.name.equals(InstrumentationConstants.INIT_METHOD_NAME)) {
        continue;
      }

      // Convert "<init>" to class simple name to match BB format
      String methodName = method.name;
      if (methodName.equals("<init>")) {
        int lastDot = className.lastIndexOf('.');
        methodName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
      }
      // DO NOT replace '.' with '$' in className - this breaks package structure in node names!
      String methodKey = className + "#" + methodName +
                        "(" + getParamTypes(method.desc) + ")";

      if (className.endsWith("StringUtils")) {
          System.out.println("[EDGE-DEBUG-ASM] Looking for " + methodKey);
      }

      List<EdgeInfo> edges = methodEdges.get(methodKey);
      if (edges != null && !edges.isEmpty()) {
        List<EdgeAnnotation> methodAnnotations = instrumentMethodWithASM(classInternalName, method, edges, probeGroup, edgeIdCounter);
        if (!methodAnnotations.isEmpty()) {
            allAnnotations.addAll(methodAnnotations);
            instrumented = true;
        }
      }
    }

    int probeCount = probeGroup.getProbes().size() - probeCountBefore;

    // If no probes were added, return original bytecode to avoid COMPUTE_FRAMES corruption
    // This is important for classes with complex bytecode (like large array initializers)
    if (probeCount == 0 || !instrumented) {
      // Detach from ClassPool to avoid interference with other classes
      try {
        ctClass.detach();
      } catch (Exception e) {
        // Ignore detach errors
      }
      return classBytes;
    }
    // Add data field, init method, and calls
    addDataField(classNode);
    addInitMethod(classNode, classInternalName, className, probeCount);
    addInitCalls(classNode, classInternalName);
    ensureStaticInitializer(classNode, classInternalName);

    // Remove all existing frame nodes to force COMPUTE_FRAMES to regenerate them
    for (MethodNode method : classNode.methods) {
      AbstractInsnNode insn = method.instructions.getFirst();
      while (insn != null) {
        AbstractInsnNode next = insn.getNext();
        if (insn.getType() == AbstractInsnNode.FRAME) {
          method.instructions.remove(insn);
        }
        insn = next;
      }
    }

    // Write instrumented class
    // Use COMPUTE_FRAMES to regenerate all stack map frames from scratch
    // Pass the target ClassLoader for correct type resolution in getCommonSuperClass
    ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES, loader);
    try {
      classNode.accept(cw);
    } catch (NegativeArraySizeException e) {
      // ASM's COMPUTE_FRAMES fails on some complex bytecode patterns.
      while (probeGroup.getProbes().size() > probeCountBefore) {
        probeGroup.getProbes().remove(probeGroup.getProbes().size() - 1);
      }
      return classBytes;
    } catch (Exception e) {
      // Any other ASM error - clear probes and return original bytecode
      while (probeGroup.getProbes().size() > probeCountBefore) {
        probeGroup.getProbes().remove(probeGroup.getProbes().size() - 1);
      }
      return classBytes;
    }
    byte[] result = cw.toByteArray();

    // Verify the instrumented bytecode by attempting to define it
    // This catches VerifyError early, before the class is actually used
    try {
      verifyBytecode(className, result, loader);

      // Verification successful - register annotations
      for (EdgeAnnotation ann : allAnnotations) {
          EdgeAnnotationRegistry.getInstance().register(ann);
      }
    } catch (VerifyError e) {
      System.err.println("[ERROR-VERIFY] Verification failed for " + className + ": " + e.getMessage());
      // Bytecode verification failed - revert to original bytecode
      synchronized (HybridEdgeInstrumentor.class) {
        verifyErrorCount++;
      }
      while (probeGroup.getProbes().size() > probeCountBefore) {
        probeGroup.getProbes().remove(probeGroup.getProbes().size() - 1);
      }
      return classBytes;
    } catch (Throwable t) {
      // Ignore other verification errors
    }

    // Accumulate timing (no per-class output for performance)
    long elapsed = System.currentTimeMillis() - startTime;
    synchronized (HybridEdgeInstrumentor.class) {
      totalInstrTime += elapsed;
      totalClassCount++;
    }

    return result;
  }

  /**
   * Analyze a method using Javassist CFG + PRS algorithm.
   */
  private List<EdgeInfo> analyzeMethodWithPRS(Block[] blocks, MethodInfo methodInfo, String methodKey) {
    List<EdgeInfo> edgeInfoList = new ArrayList<>();

    if (blocks == null || blocks.length == 0) {
      return edgeInfoList;
    }

    // Build predecessor and successor maps
    Map<Integer, Set<Integer>> predecessors = new HashMap<>();
    Map<Integer, Set<Integer>> successors = new HashMap<>();

    for (int i = 0; i < blocks.length; i++) {
      predecessors.put(i, new HashSet<>());
      successors.put(i, new HashSet<>());
    }

    // Build adjacency info
    for (int i = 0; i < blocks.length; i++) {
      Block block = blocks[i];
      for (int j = 0; j < block.exits(); j++) {
        Block exitBlock = block.exit(j);
        int exitIdx = findBlockIndex(blocks, exitBlock);
        if (exitIdx >= 0) {
          successors.get(i).add(exitIdx);
          predecessors.get(exitIdx).add(i);
        }
      }
    }

    // Apply PRS algorithm
    List<PRSAlgorithm.Block> prsBlocks = new ArrayList<>();
    for (int i = 0; i < blocks.length; i++) {
      prsBlocks.add(new PRSAlgorithm.Block(i, predecessors.get(i), successors.get(i)));
    }

    PRSAlgorithm.PRSResult prsResult = PRSAlgorithm.findRemovableBlocks(prsBlocks);
    Set<Integer> removableBlocks = prsResult.removableBlocks;
    Map<PRSAlgorithm.EdgeKey, List<Integer>> edgeAnnotations = prsResult.edgeAnnotations;

    // ==================== 计算每个 block 的 end position ====================
    // 按 position 排序，计算每个 block 的结束位置（下一个 block 的开始）
    int[] sortedPositions = Arrays.stream(blocks).mapToInt(Block::position).sorted().toArray();
    Map<Integer, Integer> blockEndPosition = new HashMap<>();
    for (int i = 0; i < sortedPositions.length; i++) {
      int endPos = (i + 1 < sortedPositions.length) ? sortedPositions[i + 1] : Integer.MAX_VALUE;
      blockEndPosition.put(sortedPositions[i], endPos);
    }

    // Build PRS edges
    Set<Integer> nonRemovable = new HashSet<>();
    for (int i = 0; i < blocks.length; i++) {
      if (!removableBlocks.contains(i)) {
        nonRemovable.add(i);
      }
    }

    // Find first non-removable block for ENTRY edge
    int firstNonRemovableId = -1;
    for (int i = 0; i < blocks.length; i++) {
      if (!removableBlocks.contains(i)) {
        firstNonRemovableId = i;
        break;
      }
    }

    if (firstNonRemovableId < 0) {
      return edgeInfoList;
    }

    // NOTE: We do NOT create a separate ENTRY edge probe.
    // In PRS, the entry is handled by the first non-removable block's probes.
    // Creating separate ENTRY probes would add redundant probes and increase overhead.

    // Create edges from each non-removable block with multiple successors
    // Use simplifiedOutEdges which contains the correct edges after PRS node removal
    for (int srcId : nonRemovable) {
      Block srcBlock = blocks[srcId];

      // Use simplified edges - these already point to non-removable successors
      Set<Integer> simplifiedSucc = prsResult.simplifiedOutEdges.get(srcId);
      if (simplifiedSucc == null || simplifiedSucc.isEmpty()) {
        continue;
      }

      int srcLine = getBlockLineNumber(srcBlock, methodInfo);
      String srcNode = methodKey + ":" + srcLine;

      for (int targetId : simplifiedSucc) {
        // Skip edges to removable targets
        if (removableBlocks.contains(targetId)) {
          continue;
        }

        List<String> annotatedNodes = new ArrayList<>();
        List<String> coveredLines = new ArrayList<>();

        // Add ALL lines from source block (not just the starting line!)
        // This is critical for blocks that span multiple lines (e.g., assignment + conditional)
        List<Integer> srcBlockLines = getBlockAllLineNumbers(srcBlock, methodInfo);
        for (int line : srcBlockLines) {
          String nodeName = methodKey + ":" + line;
          if (!coveredLines.contains(nodeName)) {
            coveredLines.add(nodeName);
          }
        }

        // Get annotated nodes directly from PRS result (no DFS needed!)
        // Also add ALL lines from each annotated block
        PRSAlgorithm.EdgeKey edgeKey = new PRSAlgorithm.EdgeKey(srcId, targetId);
        List<Integer> annotatedBlockIds = edgeAnnotations.get(edgeKey);
        if (annotatedBlockIds != null) {
          for (int blockId : annotatedBlockIds) {
            if (blockId >= 0 && blockId < blocks.length) {
              // Add all lines from this annotated block
              List<Integer> blockLines = getBlockAllLineNumbers(blocks[blockId], methodInfo);
              for (int line : blockLines) {
                String nodeName = methodKey + ":" + line;
                if (!annotatedNodes.contains(nodeName)) {
                  annotatedNodes.add(nodeName);
                }
                if (!coveredLines.contains(nodeName)) {
                  coveredLines.add(nodeName);
                }
              }
            }
          }
        }

        if (targetId >= 0 && targetId < blocks.length) {
          Block dstBlock = blocks[targetId];
          // Add all lines from destination block
          List<Integer> dstBlockLines = getBlockAllLineNumbers(dstBlock, methodInfo);
          for (int line : dstBlockLines) {
            String nodeName = methodKey + ":" + line;
            if (!coveredLines.contains(nodeName)) {
              coveredLines.add(nodeName);
            }
          }
          int dstLine = getBlockLineNumber(dstBlock, methodInfo);

          String dstNode = methodKey + ":" + dstLine;
          int probeLine = dstLine > 0 ? dstLine : srcLine;
          String edgeLabel = srcLine + "->" + dstLine;
          String edgeName = methodKey + ":" + probeLine + "#EDGE:" + edgeLabel;

          int srcEndPos = blockEndPosition.getOrDefault(srcBlock.position(), Integer.MAX_VALUE);
          edgeInfoList.add(new EdgeInfo(
              edgeName, srcNode, dstNode, probeLine,
              srcBlock.position(), srcEndPos, dstBlock.position(),  // src start, src end, dst start
              annotatedNodes, coveredLines,
              srcId, targetId
          ));
        }
      }
    }

    // FALLBACK: If no edges were generated (e.g., linear method with no branches),
    // we MUST insert at least one probe to track method execution.
    // This happens when PRS determines all blocks are equivalent or only one path exists.
    if (edgeInfoList.isEmpty() && blocks.length > 0) {
      // Find the first block (entry)
      Block entryBlock = blocks[0];
      int entryLine = getBlockLineNumber(entryBlock, methodInfo);

      // Collect ALL lines in the method as covered by this single probe
      List<String> allLines = new ArrayList<>();
      List<String> annotatedNodes = new ArrayList<>();

      for (Block b : blocks) {
        // Get ALL lines in each block, not just the starting line
        List<Integer> blockLines = getBlockAllLineNumbers(b, methodInfo);
        for (int line : blockLines) {
          String nodeName = methodKey + ":" + line;
          if (!allLines.contains(nodeName)) {
            allLines.add(nodeName);
            // Treat all subsequent blocks as "annotated" (implied by entry)
            if (b != entryBlock) {
               annotatedNodes.add(nodeName);
            }
          }
        }
      }

      String srcNode = methodKey + ":ENTRY";
      String dstNode = methodKey + ":" + entryLine;
      String edgeName = methodKey + ":" + entryLine + "#EDGE:ENTRY"; // Special entry edge

      // Insert probe at the beginning of the first block
      // For ENTRY edges: srcBlockPosition=-1 (no source block), srcBlockEndPosition=MAX_VALUE
      edgeInfoList.add(new EdgeInfo(
          edgeName, srcNode, dstNode, entryLine,
          -1, Integer.MAX_VALUE, entryBlock.position(),  // srcPos=-1, srcEndPos=MAX, dstPos=first block
          annotatedNodes, allLines,
          -1, 0 // -1 as srcId to indicate ENTRY
      ));
    }

    return edgeInfoList;
  }

  /**
   * Instrument a method using ASM based on edge information from Javassist.
   * 使用 Label.getOffset() 做精确的字节码偏移量映射。
   */
  private List<EdgeAnnotation> instrumentMethodWithASM(String classInternalName, MethodNode method,
                                         List<EdgeInfo> edges, ProbeGroup probeGroup,
                                         java.util.concurrent.atomic.AtomicInteger edgeIdCounter) {
    InsnList insns = method.instructions;
    List<EdgeAnnotation> annotations = new ArrayList<>();

    // ==================== 关键改进：使用 Label.getOffset() ====================
    // 建立 字节码偏移量 -> LabelNode 的精确映射（来自 ClassReader 的真实 offset）
    // 使用 TreeMap 以支持 findNearestLabel() 的 floorEntry() 查找
    TreeMap<Integer, LabelNode> offsetToLabel = buildOffsetToLabelMap(insns);

    // Register probes for all edges
    for (int i = 0; i < edges.size(); i++) {
      EdgeInfo edgeInfo = edges.get(i);

      // Create node for this edge
      Node node = new Node(edgeInfo.edgeName, edgeInfo.lineNumber, true, NodeType.LINE);
      Probe probe = probeGroup.registerProbe(node, null);

      // Use the actual probe ID assigned by ProbeGroup (local to class)
      int probeId = probe.getArrayIndex();
      edgeInfo.probeId = probeId;

      // Register edge annotation
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

      // Use class-level unique edgeId (not method-local i)
      int edgeId = edgeIdCounter.getAndIncrement();
      EdgeAnnotation annotation = new EdgeAnnotation(
          edgeId,
          probeId,
          edgeInfo.edgeName.substring(0, edgeInfo.edgeName.indexOf(':')),
          edgeInfo.fromNode,
          edgeInfo.toNode,
          new ArrayList<>(edgeInfo.annotatedNodes),
          coveredLineNumbers,
          probeGroup.getHash()
      );
      annotations.add(annotation);
    }

    // 关键改进：按 srcBlockPosition 分组，避免同一条件的两条边互相干扰
    // 使用 TreeMap 以降序排列（从方法末尾往前插，更稳定）
    TreeMap<Integer, List<EdgeInfo>> edgesBySrcBlock = new TreeMap<>(Collections.reverseOrder());
    for (EdgeInfo edge : edges) {
      edgesBySrcBlock.computeIfAbsent(edge.srcBlockPosition, k -> new ArrayList<>()).add(edge);
    }

    // 对每个 srcBlock 的所有边一起处理（按位置从后往前，避免指令插入干扰）
    for (Map.Entry<Integer, List<EdgeInfo>> entry : edgesBySrcBlock.entrySet()) {
      int srcBlockPos = entry.getKey();
      List<EdgeInfo> blockEdges = entry.getValue();
      // 使用 findNearestLabel 作为兜底（精确匹配 + 最近前驱 label）
      LabelNode srcLabel = findNearestLabel(offsetToLabel, srcBlockPos);

      if (blockEdges.size() == 1) {
        // 单边：用原来的方法
        EdgeInfo edge = blockEdges.get(0);
        LabelNode dstLabel = findNearestLabel(offsetToLabel, edge.dstBlockPosition);
        insertEdgeProbeByBlock(classInternalName, insns, edge, edge.probeId, srcLabel, dstLabel);
      } else {
        // 多边（条件分支）：一起处理，避免干扰
        insertEdgeProbesForConditionalBlock(classInternalName, insns, blockEdges, srcLabel, offsetToLabel);
      }
    }

    return annotations;
  }

  /**
   * Insert edge probe using block-based scanning (not line-based).
   *
   * 核心思路：
   * 1. 用 srcLabel/dstLabel 定位 block 范围
   * 2. 在 srcBlock 内找 terminator（jump/switch/return/athrow）
   * 3. 根据 terminator 类型决定插入位置：
   *    - GOTO to dst: 插在 GOTO 前
   *    - Conditional jump to dst: 用就地反转 trampoline
   *    - Conditional jump elsewhere (fall-through to dst): 插在 jump 后
   *    - Pure fall-through: 插在 block 最后一条指令后
   */
  private void insertEdgeProbeByBlock(String classInternalName, InsnList insns,
                                      EdgeInfo edge, int probeId,
                                      LabelNode srcLabel, LabelNode dstLabel) {

    // Handle ENTRY edges - insert at method start
    if (edge.fromBlockIndex < 0 || edge.srcBlockPosition < 0 ||
        (edge.fromNode != null && edge.fromNode.contains(":ENTRY"))) {
      AbstractInsnNode firstInsn = findFirstExecutable(insns);
      if (firstInsn != null) {
        insns.insertBefore(firstInsn, createProbeCode(classInternalName, probeId));
      }
      return;
    }

    // 如果找不到 srcLabel，退回到基于行号的方式
    if (srcLabel == null) {
      int srcLine = extractLineNumber(edge.fromNode);
      int dstLine = extractLineNumber(edge.toNode);
      System.err.println("[EDGE-DEBUG] probe" + probeId + " (" + srcLine + "->" + dstLine + "): srcLabel=null, falling back to line-based");
      if (srcLine > 0) {
        insertEdgeProbeByLine(classInternalName, insns, edge, probeId);
      }
      return;
    }

    // 从 srcLabel 开始扫描，找 terminator
    // 关键改进：使用 srcBlockEndPosition 作为停止条件，而不是检查 label
    AbstractInsnNode current = srcLabel.getNext();
    JumpInsnNode jumpToDst = null;        // 跳转到 dstBlock 的 jump
    JumpInsnNode conditionalAway = null;  // 条件跳转到其他地方（fall-through 到 dst）
    AbstractInsnNode lastExecInBlock = null;
    int searchCount = 0;

    while (current != null && searchCount < 200) {
      // 关键改进：用 srcBlockEndPosition 作为停止条件
      // 如果当前 label 的 offset >= srcBlockEndPosition，说明已经离开了 srcBlock
      if (current instanceof LabelNode && current != srcLabel) {
        try {
          int labelOffset = ((LabelNode) current).getLabel().getOffset();
          if (labelOffset >= edge.srcBlockEndPosition) {
            // 已经到达或超过 srcBlock 的末尾，停止
            break;
          }
        } catch (IllegalStateException e) {
          // offset 未解析，继续
        }
      }

      // 记录最后一条可执行指令
      int type = current.getType();
      if (type != AbstractInsnNode.LABEL && type != AbstractInsnNode.LINE && type != AbstractInsnNode.FRAME) {
        lastExecInBlock = current;
      }

      // 检查是否是 jump 指令
      if (current instanceof JumpInsnNode) {
        JumpInsnNode jump = (JumpInsnNode) current;
        int opcode = jump.getOpcode();

        // 关键改进：使用 jumpTargetsDstBlock() 检查跳转目标
        // 这样即使 dstLabel==null 也能通过 offset 匹配
        if (jumpTargetsDstBlock(jump, edge.dstBlockPosition, dstLabel)) {
          jumpToDst = jump;
          break;
        } else if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) {
          // 条件跳转到其他地方 - 可能是 fall-through edge
          conditionalAway = jump;
        } else if (opcode == Opcodes.GOTO) {
          // GOTO 到其他地方，这条 edge 可能不存在或者是另一条边
          break;
        }
      }

      // 检查是否是 terminator（return/athrow）
      int opcode = current.getOpcode();
      if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
        break;
      }

      // 检查是否是 switch
      if (current instanceof TableSwitchInsnNode || current instanceof LookupSwitchInsnNode) {
        // TODO: 处理 switch edge（暂时跳过）
        break;
      }

      current = current.getNext();
      searchCount++;
    }

    // Debug output
    int srcLine = extractLineNumber(edge.fromNode);
    int dstLine = extractLineNumber(edge.toNode);
    String debugPrefix = "[EDGE-DEBUG] probe" + probeId + " (" + srcLine + "->" + dstLine + "): ";

    // 根据找到的情况插入 probe
    if (jumpToDst != null) {
      // CASE 1: 有显式跳转到 dst
      int opcode = jumpToDst.getOpcode();
      if (opcode == Opcodes.GOTO) {
        // GOTO - 插在 GOTO 前
        System.err.println(debugPrefix + "CASE1-GOTO, insert before GOTO");
        insns.insertBefore(jumpToDst, createProbeCode(classInternalName, probeId));
      } else {
        // 条件跳转 - 使用就地反转 trampoline
        System.err.println(debugPrefix + "CASE1-COND, use local trampoline");
        instrumentConditionalTakenEdge(insns, jumpToDst, createProbeCode(classInternalName, probeId));
      }
    } else if (conditionalAway != null) {
      // CASE 2: 有条件跳转到其他地方，fall-through 到 dst
      // 插在条件跳转后面
      System.err.println(debugPrefix + "CASE2-FALLTHRU, insert after conditional");
      insns.insert(conditionalAway, createProbeCode(classInternalName, probeId));
    } else if (lastExecInBlock != null) {
      // CASE 3: 纯 fall-through，无跳转
      // 插在 block 最后一条指令后
      System.err.println(debugPrefix + "CASE3-PURE, insert after lastExec, srcLabel=" + (srcLabel != null) + ", dstLabel=" + (dstLabel != null));
      insns.insert(lastExecInBlock, createProbeCode(classInternalName, probeId));
    } else {
      System.err.println(debugPrefix + "NO-INSERT (srcLabel=" + (srcLabel != null) + ", dstLabel=" + (dstLabel != null) + ")");
    }
  }

  /**
   * 处理同一个 srcBlock 的多条边（条件分支情况）。
   * 关键：一起处理所有边，避免修改字节码时互相干扰。
   *
   * 对于条件跳转 (if-then-else):
   * - taken edge: 条件成立时走的路径
   * - fall-through edge: 条件不成立时走的路径
   *
   * 变换后的结构：
   *   IFGT Lskip        // 反转条件
   *   probe(taken)      // taken edge 的探针
   *   GOTO Ldest
   *   Lskip:
   *   probe(fallthru)   // fall-through edge 的探针
   *   <原 fall-through 代码>
   */
  private void insertEdgeProbesForConditionalBlock(String classInternalName, InsnList insns,
                                                    List<EdgeInfo> blockEdges, LabelNode srcLabel,
                                                    TreeMap<Integer, LabelNode> offsetToLabel) {
    if (blockEdges.isEmpty()) return;

    // 获取 srcBlock 信息（所有边共享同一个 srcBlock）
    EdgeInfo firstEdge = blockEdges.get(0);
    int srcBlockEndPos = firstEdge.srcBlockEndPosition;

    // 如果是 ENTRY 边，单独处理
    if (firstEdge.srcBlockPosition < 0) {
      for (EdgeInfo edge : blockEdges) {
        AbstractInsnNode firstInsn = findFirstExecutable(insns);
        if (firstInsn != null) {
          insns.insertBefore(firstInsn, createProbeCode(classInternalName, edge.probeId));
        }
      }
      return;
    }

    // 如果找不到 srcLabel，回退到行号方式
    if (srcLabel == null) {
      for (EdgeInfo edge : blockEdges) {
        LabelNode dstLabel = findNearestLabel(offsetToLabel, edge.dstBlockPosition);
        insertEdgeProbeByBlock(classInternalName, insns, edge, edge.probeId, srcLabel, dstLabel);
      }
      return;
    }

    // 扫描 srcBlock 找到 terminator
    AbstractInsnNode current = srcLabel.getNext();
    JumpInsnNode conditionalJump = null;
    int searchCount = 0;

    while (current != null && searchCount < 200) {
      // 用 srcBlockEndPosition 作为停止条件
      if (current instanceof LabelNode && current != srcLabel) {
        try {
          int labelOffset = ((LabelNode) current).getLabel().getOffset();
          if (labelOffset >= srcBlockEndPos) {
            break;
          }
        } catch (IllegalStateException e) {
          // continue
        }
      }

      if (current instanceof JumpInsnNode) {
        JumpInsnNode jump = (JumpInsnNode) current;
        int opcode = jump.getOpcode();
        if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) {
          // 找到条件跳转
          conditionalJump = jump;
          break;
        } else if (opcode == Opcodes.GOTO) {
          // 无条件跳转，不应该有多条边
          break;
        }
      }

      // return/athrow
      int opcode = current.getOpcode();
      if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) || opcode == Opcodes.ATHROW) {
        break;
      }

      // switch
      if (current instanceof TableSwitchInsnNode || current instanceof LookupSwitchInsnNode) {
        // TODO: 处理 switch
        break;
      }

      current = current.getNext();
      searchCount++;
    }

    // Debug
    int srcLine = extractLineNumber(firstEdge.fromNode);
    String debugPrefix = "[EDGE-MULTI] srcLine=" + srcLine + " edges=" + blockEdges.size() + ": ";

    if (conditionalJump == null) {
      // 没找到条件跳转，按单边方式处理
      System.err.println(debugPrefix + "no conditional found, falling back");
      for (EdgeInfo edge : blockEdges) {
        LabelNode dstLabel = findNearestLabel(offsetToLabel, edge.dstBlockPosition);
        insertEdgeProbeByBlock(classInternalName, insns, edge, edge.probeId, srcLabel, dstLabel);
      }
      return;
    }

    // 分类边：找出 taken edge 和 fall-through edge
    EdgeInfo takenEdge = null;
    EdgeInfo fallthruEdge = null;

    int jumpTargetOffset = -1;
    try {
      jumpTargetOffset = conditionalJump.label.getLabel().getOffset();
    } catch (IllegalStateException e) {
      // ignore
    }

    for (EdgeInfo edge : blockEdges) {
      if (jumpTargetOffset >= 0 && edge.dstBlockPosition == jumpTargetOffset) {
        takenEdge = edge;
      } else {
        fallthruEdge = edge;
      }
    }

    // 处理 taken edge 和 fall-through edge
    if (takenEdge != null && fallthruEdge != null) {
      // 两条边都存在：使用 trampoline，同时插入两个探针
      int invertedOpcode = invertIfOpcode(conditionalJump.getOpcode());
      if (invertedOpcode < 0) {
        // 不能反转，回退
        System.err.println(debugPrefix + "cannot invert opcode, falling back");
        for (EdgeInfo edge : blockEdges) {
          LabelNode dstLabel = findNearestLabel(offsetToLabel, edge.dstBlockPosition);
          insertEdgeProbeByBlock(classInternalName, insns, edge, edge.probeId, srcLabel, dstLabel);
        }
        return;
      }

      System.err.println(debugPrefix + "taken=" + takenEdge.probeId + ", fallthru=" + fallthruEdge.probeId);

      LabelNode Lskip = new LabelNode();
      LabelNode Ldest = conditionalJump.label;

      // 创建反转后的跳转
      JumpInsnNode newJump = new JumpInsnNode(invertedOpcode, Lskip);

      // 构建 patch: probe(taken) + GOTO Ldest + Lskip: + probe(fallthru)
      InsnList patch = new InsnList();
      patch.add(createProbeCode(classInternalName, takenEdge.probeId));  // taken probe
      patch.add(new JumpInsnNode(Opcodes.GOTO, Ldest));
      patch.add(Lskip);
      patch.add(createProbeCode(classInternalName, fallthruEdge.probeId));  // fallthru probe

      // 替换原 jump 并插入 patch
      insns.set(conditionalJump, newJump);
      insns.insert(newJump, patch);

    } else if (takenEdge != null) {
      // 只有 taken edge
      System.err.println(debugPrefix + "only taken=" + takenEdge.probeId);
      instrumentConditionalTakenEdge(insns, conditionalJump, createProbeCode(classInternalName, takenEdge.probeId));

    } else if (fallthruEdge != null) {
      // 只有 fall-through edge
      System.err.println(debugPrefix + "only fallthru=" + fallthruEdge.probeId);
      insns.insert(conditionalJump, createProbeCode(classInternalName, fallthruEdge.probeId));

    } else {
      System.err.println(debugPrefix + "NO-INSERT");
    }
  }

  /**
   * 检查 jump 指令是否跳转到 dstBlock（使用 offset 比较，处理 dstLabel==null 的情况）
   * 这是关键改进：dstLabel 经常为 null（很多 block 开始没有 label），
   * 但我们可以通过比较 jump 目标的 offset 和 dstBlockPosition 来判断。
   */
  private boolean jumpTargetsDstBlock(JumpInsnNode jump, int dstBlockPos, LabelNode dstLabel) {
    // 如果有 dstLabel 且直接匹配
    if (dstLabel != null && jump.label == dstLabel) {
      return true;
    }
    // 用 offset 对齐（即使 dstLabel 为 null 也能工作）
    try {
      int jumpTargetOffset = jump.label.getLabel().getOffset();
      return jumpTargetOffset == dstBlockPos;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  /**
   * 对条件跳转的 taken edge 使用就地反转 trampoline。
   * JaCoCo 风格：反转条件，probe 放在 fall-through 上，然后 GOTO 原目标。
   *
   * 原来：
   *   IFNE Ldest
   *   ... (fall-through path)
   *
   * 改成：
   *   IFEQ Lskip        // 反转
   *   PROBE(edge)
   *   GOTO Ldest
   *   Lskip:
   *   ... (原 fall-through)
   */
  private void instrumentConditionalTakenEdge(InsnList insns, JumpInsnNode jump, InsnList probe) {
    int invertedOpcode = invertIfOpcode(jump.getOpcode());
    if (invertedOpcode < 0) {
      // 不是条件跳转，回退到方法末尾 trampoline
      instrumentWithMethodEndTrampoline(insns, jump, probe);
      return;
    }

    LabelNode Lskip = new LabelNode();
    LabelNode Ldest = jump.label;

    // 创建反转后的跳转指令
    JumpInsnNode newJump = new JumpInsnNode(invertedOpcode, Lskip);

    // 创建 patch: probe + GOTO Ldest + Lskip
    InsnList patch = new InsnList();
    patch.add(probe);
    patch.add(new JumpInsnNode(Opcodes.GOTO, Ldest));
    patch.add(Lskip);

    // 替换原 jump 并插入 patch
    insns.set(jump, newJump);
    insns.insert(newJump, patch);
  }

  /**
   * 回退方案：在方法末尾插入 trampoline
   */
  private void instrumentWithMethodEndTrampoline(InsnList insns, JumpInsnNode jump, InsnList probe) {
    LabelNode trampolineLabel = new LabelNode();
    LabelNode originalTarget = jump.label;

    // 找方法末尾
    AbstractInsnNode lastInsn = insns.getLast();
    while (lastInsn != null &&
           (lastInsn.getType() == AbstractInsnNode.LABEL ||
            lastInsn.getType() == AbstractInsnNode.LINE ||
            lastInsn.getType() == AbstractInsnNode.FRAME)) {
      lastInsn = lastInsn.getPrevious();
    }

    if (lastInsn != null) {
      InsnList trampoline = new InsnList();
      trampoline.add(trampolineLabel);
      trampoline.add(probe);
      trampoline.add(new JumpInsnNode(Opcodes.GOTO, originalTarget));
      insns.insert(lastInsn, trampoline);
      jump.label = trampolineLabel;
    }
  }

  /**
   * 基于行号的 fallback 方案（当无法通过 offset 找到 label 时使用）
   */
  private void insertEdgeProbeByLine(String classInternalName, InsnList insns,
                                     EdgeInfo edge, int probeId) {
    int srcLine = extractLineNumber(edge.fromNode);
    int destLine = extractLineNumber(edge.toNode);

    if (srcLine < 0 || destLine < 0) {
      return;
    }

    AbstractInsnNode current = findInstructionAtLine(insns, srcLine);
    if (current == null) {
      return;
    }

    JumpInsnNode jumpToDest = null;
    JumpInsnNode lastConditionalAway = null;
    int searchCount = 0;

    while (current != null && searchCount < 300) {
      if (current instanceof LineNumberNode) {
        int newLine = ((LineNumberNode) current).line;
        if (newLine == destLine || newLine > destLine + 20) {
          break;
        }
      }

      if (current instanceof JumpInsnNode) {
        JumpInsnNode jump = (JumpInsnNode) current;
        int targetLine = findLineNumberAfter(jump.label);

        if (targetLine == destLine) {
          jumpToDest = jump;
          break;
        } else if (jump.getOpcode() != Opcodes.GOTO) {
          lastConditionalAway = jump;
        }
      }

      current = current.getNext();
      searchCount++;
    }

    if (jumpToDest != null) {
      if (jumpToDest.getOpcode() == Opcodes.GOTO) {
        insns.insertBefore(jumpToDest, createProbeCode(classInternalName, probeId));
      } else {
        instrumentConditionalTakenEdge(insns, jumpToDest, createProbeCode(classInternalName, probeId));
      }
    } else if (lastConditionalAway != null) {
      insns.insert(lastConditionalAway, createProbeCode(classInternalName, probeId));
    } else {
      AbstractInsnNode lastInsnOfSrc = findLastInsnOfLine(insns, srcLine);
      if (lastInsnOfSrc != null) {
        insns.insert(lastInsnOfSrc, createProbeCode(classInternalName, probeId));
      }
    }
  }

  /**
   * Find the last executable instruction of a given line.
   */
  private AbstractInsnNode findLastInsnOfLine(InsnList insns, int targetLine) {
    AbstractInsnNode current = findInstructionAtLine(insns, targetLine);
    if (current == null) return null;

    AbstractInsnNode lastExec = null;

    while (current != null) {
      if (current instanceof LineNumberNode) {
        int newLine = ((LineNumberNode) current).line;
        if (newLine != targetLine) {
          break;
        }
      }

      if (current.getType() != AbstractInsnNode.LABEL &&
          current.getType() != AbstractInsnNode.LINE &&
          current.getType() != AbstractInsnNode.FRAME) {
        lastExec = current;
      }

      current = current.getNext();
    }

    return lastExec;
  }


  /**
   * Helper methods
   */
  private int findBlockIndex(Block[] blocks, Block target) {
    for (int i = 0; i < blocks.length; i++) {
      if (blocks[i].position() == target.position()) {
        return i;
      }
    }
    return -1;
  }

  private int getBlockLineNumber(Block block, MethodInfo methodInfo) {
    try {
      return methodInfo.getLineNumber(block.position());
    } catch (Exception e) {
      return -1;
    }
  }

  /**
   * Get ALL line numbers covered by a basic block.
   * A basic block may span multiple source lines (e.g., assignment + conditional on different lines).
   * This is critical for correct node coverage recovery.
   */
  private List<Integer> getBlockAllLineNumbers(Block block, MethodInfo methodInfo) {
    List<Integer> lines = new ArrayList<>();
    try {
      int startPos = block.position();
      int endPos = startPos + block.length();

      // Iterate through all bytecode positions in the block and collect unique line numbers
      for (int pos = startPos; pos < endPos; pos++) {
        int line = methodInfo.getLineNumber(pos);
        if (line > 0 && !lines.contains(line)) {
          lines.add(line);
        }
      }
    } catch (Exception e) {
      // Fallback to just the starting line
      int startLine = getBlockLineNumber(block, methodInfo);
      if (startLine > 0) {
        lines.add(startLine);
      }
    }
    return lines;
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

  /**
   * Find the first executable instruction at or after the given source line number.
   */
  private AbstractInsnNode findInstructionAtLine(InsnList insns, int targetLine) {
    // First, find the LINE instruction for the target line
    LineNumberNode targetLineNode = null;
    for (int i = 0; i < insns.size(); i++) {
      AbstractInsnNode insn = insns.get(i);
      if (insn instanceof LineNumberNode) {
        LineNumberNode lineNode = (LineNumberNode) insn;
        if (lineNode.line == targetLine) {
          targetLineNode = lineNode;
          break;
        }
      }
    }

    // If we found the line, find the first executable instruction after it
    if (targetLineNode != null) {
      AbstractInsnNode insn = targetLineNode.getNext();
      while (insn != null) {
        if (insn.getType() != AbstractInsnNode.LABEL &&
            insn.getType() != AbstractInsnNode.LINE &&
            insn.getType() != AbstractInsnNode.FRAME) {
          return insn;
        }
        insn = insn.getNext();
      }
    }

    // Fallback: return first executable instruction
    return findFirstExecutable(insns);
  }


  /**
   * Find the line number associated with or after a label node.
   */
  private int findLineNumberAfter(AbstractInsnNode node) {
    AbstractInsnNode current = node;
    int count = 0;
    while (current != null && count < 50) {
      if (current instanceof LineNumberNode) {
        return ((LineNumberNode) current).line;
      }
      current = current.getNext();
      count++;
    }
    return -1;
  }

  /**
   * Extract line number from node name (format: "class#method:line").
   */
  private int extractLineNumber(String nodeName) {
    int colonIdx = nodeName.lastIndexOf(':');
    if (colonIdx > 0) {
      try {
        String lineStr = nodeName.substring(colonIdx + 1);
        // Handle ENTRY nodes
        if (lineStr.equals("ENTRY")) return -1;
        return Integer.parseInt(lineStr);
      } catch (NumberFormatException e) {
        return -1;
      }
    }
    return -1;
  }

  /**
   * Create probe bytecode: $gzoltarData[probeId] = true
   * Uses optimal opcode based on index size:
   * - BIPUSH for 0-127
   * - SIPUSH for 128-32767
   * - LDC for 32768+ (fixes overflow issue with SIPUSH)
   */
  private InsnList createProbeCode(String classInternalName, int probeId) {
    InsnList code = new InsnList();

    code.add(new FieldInsnNode(Opcodes.GETSTATIC,
        classInternalName,
        InstrumentationConstants.FIELD_NAME,
        InstrumentationConstants.FIELD_DESC_BYTECODE));

    // Use optimal opcode based on index size:
    // - BIPUSH for index 0-127 (2 bytes, fastest)
    // - SIPUSH for index 128-32767 (3 bytes)
    // - LDC for index 32768+ (SIPUSH only supports signed short range)
    if (probeId >= 0 && probeId <= 127) {
      code.add(new IntInsnNode(Opcodes.BIPUSH, probeId));
    } else if (probeId <= 32767) {
      code.add(new IntInsnNode(Opcodes.SIPUSH, probeId));
    } else {
      // LDC for large probe IDs (avoids SIPUSH overflow)
      code.add(new LdcInsnNode(probeId));
    }
    code.add(new InsnNode(Opcodes.ICONST_1));
    code.add(new InsnNode(Opcodes.BASTORE));

    return code;
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
   * Add $gzoltarData field to the class.
   */
  private void addDataField(ClassNode classNode) {
    // Check if field already exists
    for (org.objectweb.asm.tree.FieldNode field : classNode.fields) {
      if (field.name.equals(InstrumentationConstants.FIELD_NAME)) {
        return;  // Field already exists
      }
    }

    // Determine access flags based on class type
    // Must match InstrumentationConstants.FIELD_ACC and FIELD_INTF_ACC
    int access;
    if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
      // Interface: public static final synthetic (NO TRANSIENT - not allowed on interfaces)
      access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL |
               Opcodes.ACC_SYNTHETIC;
    } else {
      // Class: private static synthetic transient
      access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC |
               Opcodes.ACC_SYNTHETIC | Opcodes.ACC_TRANSIENT;
    }

    // Create field: boolean[] $gzoltarData = null
    org.objectweb.asm.tree.FieldNode field = new org.objectweb.asm.tree.FieldNode(
        access,
        InstrumentationConstants.FIELD_NAME,
        InstrumentationConstants.FIELD_DESC_BYTECODE,  // "[Z" (boolean array)
        null,  // signature
        null   // initial value (null)
    );
    classNode.fields.add(field);
  }

  /**
   * Add $gzoltarInit method to the class.
   */
  private void addInitMethod(ClassNode classNode, String classInternalName, String className, int probeCount) {
    int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
      access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
    }

    MethodNode initMethod = new MethodNode(access, InstrumentationConstants.INIT_METHOD_NAME, "()V", null, null);
    InsnList insns = new InsnList();

    insns.add(new FieldInsnNode(Opcodes.GETSTATIC, classInternalName,
        InstrumentationConstants.FIELD_NAME, InstrumentationConstants.FIELD_DESC_BYTECODE));
    LabelNode afterInit = new LabelNode();
    insns.add(new JumpInsnNode(Opcodes.IFNONNULL, afterInit));

    insns.add(new InsnNode(Opcodes.ICONST_3));
    insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
    insns.add(new InsnNode(Opcodes.DUP));
    insns.add(new InsnNode(Opcodes.ICONST_0));
    insns.add(new LdcInsnNode(currentClassHash != null ? currentClassHash : ""));
    insns.add(new InsnNode(Opcodes.AASTORE));
    insns.add(new InsnNode(Opcodes.DUP));
    insns.add(new InsnNode(Opcodes.ICONST_1));
    insns.add(new LdcInsnNode(className));
    insns.add(new InsnNode(Opcodes.AASTORE));
    insns.add(new InsnNode(Opcodes.DUP));
    insns.add(new InsnNode(Opcodes.ICONST_2));
    insns.add(new LdcInsnNode(String.valueOf(probeCount)));
    insns.add(new InsnNode(Opcodes.AASTORE));
    insns.add(new VarInsnNode(Opcodes.ASTORE, 0));

    insns.add(new FieldInsnNode(Opcodes.GETSTATIC, InstrumentationConstants.SYSTEM_CLASS_NAME,
        InstrumentationConstants.SYSTEM_CLASS_FIELD_NAME, "Ljava/lang/Object;"));
    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals",
        "(Ljava/lang/Object;)Z", false));
    insns.add(new InsnNode(Opcodes.POP));

    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
    insns.add(new InsnNode(Opcodes.ICONST_0));
    insns.add(new InsnNode(Opcodes.AALOAD));
    insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Z"));
    insns.add(new FieldInsnNode(Opcodes.PUTSTATIC, classInternalName,
        InstrumentationConstants.FIELD_NAME, InstrumentationConstants.FIELD_DESC_BYTECODE));

    insns.add(afterInit);
    // No need to add FrameNode manually - COMPUTE_FRAMES will handle it
    insns.add(new InsnNode(Opcodes.RETURN));

    initMethod.instructions = insns;
    initMethod.maxStack = 4;
    initMethod.maxLocals = 1;
    classNode.methods.add(initMethod);
  }

  /**
   * Add $gzoltarInit() call at the beginning of each method.
   */
  private void addInitCalls(ClassNode classNode, String classInternalName) {
    for (MethodNode method : classNode.methods) {
      if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
        continue;
      }
      if (method.name.equals(InstrumentationConstants.INIT_METHOD_NAME)) {
        continue;
      }
      if (method.name.equals("<clinit>")) {
        continue;
      }

      InsnList initCall = new InsnList();
      initCall.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classInternalName,
          InstrumentationConstants.INIT_METHOD_NAME, "()V", false));

      AbstractInsnNode firstInsn = method.instructions.getFirst();
      while (firstInsn != null && (firstInsn.getType() == AbstractInsnNode.LABEL ||
             firstInsn.getType() == AbstractInsnNode.LINE ||
             firstInsn.getType() == AbstractInsnNode.FRAME)) {
        firstInsn = firstInsn.getNext();
      }

      if (firstInsn != null) {
        method.instructions.insertBefore(firstInsn, initCall);
      } else {
        method.instructions.insert(initCall);
      }
    }
  }

  /**
   * Ensure static initializer exists and calls $gzoltarInit.
   */
  private void ensureStaticInitializer(ClassNode classNode, String classInternalName) {
    MethodNode clinit = null;
    for (MethodNode method : classNode.methods) {
      if (method.name.equals("<clinit>")) {
        clinit = method;
        break;
      }
    }

    if (clinit == null) {
      clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      clinit.instructions = new InsnList();
      clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classInternalName,
          InstrumentationConstants.INIT_METHOD_NAME, "()V", false));
      clinit.instructions.add(new InsnNode(Opcodes.RETURN));
      clinit.maxStack = 1;
      clinit.maxLocals = 0;
      classNode.methods.add(clinit);
    } else {
      InsnList initCall = new InsnList();
      initCall.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classInternalName,
          InstrumentationConstants.INIT_METHOD_NAME, "()V", false));

      AbstractInsnNode firstInsn = clinit.instructions.getFirst();
      while (firstInsn != null && (firstInsn.getType() == AbstractInsnNode.LABEL ||
             firstInsn.getType() == AbstractInsnNode.LINE ||
             firstInsn.getType() == AbstractInsnNode.FRAME)) {
        firstInsn = firstInsn.getNext();
      }

      if (firstInsn != null) {
        clinit.instructions.insertBefore(firstInsn, initCall);
      } else {
        clinit.instructions.insert(initCall);
      }
    }
  }

  /**
   * Verify bytecode by attempting to define the class in an isolated ClassLoader.
   * Throws VerifyError if the bytecode is invalid.
   */
  private void verifyBytecode(String className, byte[] bytecode, ClassLoader loader) throws VerifyError {
    try {
      // Use the provided loader as parent to ensure visibility of dependencies
      // This is crucial for verification of classes that depend on other classes (e.g. JUnit TestCase)
      ClassLoader verifier = new ClassLoader(loader) {
        public Class<?> verify(String name, byte[] b) {
          return defineClass(name, b, 0, b.length);
        }
      };
      // Attempt to define - this will throw VerifyError if bytecode is invalid
      java.lang.reflect.Method m = verifier.getClass().getDeclaredMethod("verify", String.class, byte[].class);
      m.setAccessible(true);
      m.invoke(verifier, className, bytecode);
    } catch (java.lang.reflect.InvocationTargetException e) {
      if (e.getCause() instanceof VerifyError) {
        throw (VerifyError) e.getCause();
      }
      // Other errors might indicate class loading issues - also treat as verify error
      if (e.getCause() instanceof Error) {
        throw new VerifyError("Verification failed: " + e.getCause().getMessage());
      }
    } catch (Exception e) {
      // Ignore reflection errors
    }
  }

  /**
   * Fast ClassWriter with cached class loading for frame computation.
   * Uses the TARGET class's ClassLoader (not agent's) for correct type resolution.
   */
  private static class SafeClassWriter extends ClassWriter {
    private final ClassLoader loader;
    private static final Map<String, Class<?>> classCache = new java.util.concurrent.ConcurrentHashMap<>();

    public SafeClassWriter(ClassReader classReader, int flags, ClassLoader targetLoader) {
      super(classReader, flags);
      // Use target class's loader for correct type resolution in multi-classloader environments
      this.loader = (targetLoader != null) ? targetLoader : SafeClassWriter.class.getClassLoader();
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
      // Fast path: identical types
      if (type1.equals(type2)) {
        return type1;
      }

      // Fast path: one is Object
      if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) {
        return "java/lang/Object";
      }

      try {
        Class<?> class1 = loadClassCached(type1);
        Class<?> class2 = loadClassCached(type2);

        if (class1 == null || class2 == null) {
          return "java/lang/Object";
        }

        if (class1.isAssignableFrom(class2)) {
          return type1;
        }
        if (class2.isAssignableFrom(class1)) {
          return type2;
        }
        if (class1.isInterface() || class2.isInterface()) {
          return "java/lang/Object";
        }

        // Walk up the superclass chain
        Class<?> superClass = class1.getSuperclass();
        while (superClass != null) {
          if (superClass.isAssignableFrom(class2)) {
            return superClass.getName().replace('.', '/');
          }
          superClass = superClass.getSuperclass();
        }

        return "java/lang/Object";
      } catch (Throwable t) {
        return "java/lang/Object";
      }
    }

    private Class<?> loadClassCached(String internalName) {
      String className = internalName.replace('/', '.');
      return classCache.computeIfAbsent(className, name -> {
        try {
          return Class.forName(name, false, loader);
        } catch (Throwable t) {
          return null;
        }
      });
    }
  }

}
