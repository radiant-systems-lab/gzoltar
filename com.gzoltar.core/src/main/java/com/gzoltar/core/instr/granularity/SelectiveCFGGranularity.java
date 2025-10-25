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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;

import com.gzoltar.core.instr.cfg.BasicBlockNode;
import com.gzoltar.core.instr.cfg.CFGBuilder;
import com.gzoltar.core.instr.cfg.ControlFlowGraph;
import com.gzoltar.core.instr.cfg.PathRecoveryOrder;
import javassist.CtClass;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;

/**
 * A selective CFG-based granularity that uses the Path Recovery Set (PRS) algorithm
 * to minimize the number of basic blocks that need to be instrumented while still
 * maintaining full path coverage information.
 */
public class SelectiveCFGGranularity extends AbstractGranularity {

    private Queue<Integer> blocks = new LinkedList<Integer>();  // All basic blocks (same as BasicBlockGranularity)
    private Set<Integer> selectedBlockSet = new HashSet<Integer>();  // Blocks selected by PRS algorithm
    private ControlFlowGraph cfg;
    private Map<Integer, Integer> offsetToBlockId = new HashMap<Integer, Integer>();
    private Integer lastInstrumentedOffset = null;

    // --- NEW: Data structures for edge-based analysis ---
    private Map<Pair<Integer, Integer>, String> edgeLabels = new HashMap<>();
    private Map<Pair<Integer, Integer>, Integer> edgeIds = new HashMap<>();
    private Map<Pair<Integer, Integer>, List<BasicBlockNode>> edgePaths = new HashMap<>();  // Store full path for each edge
    private int nextEdgeId = 0;

    // Store PRS result for on-demand path expansion
    private ControlFlowGraph.PRSResult prsResult;

    public SelectiveCFGGranularity(final CtClass ctClass, final MethodInfo methodInfo) {
        super(ctClass, methodInfo);
        initializeCFG(ctClass, methodInfo);
    }

    /**
     * NEW: Expose the offsets of blocks that should be instrumented.
     */
    public Set<Integer> getSelectedBlockOffsets() {
        return this.selectedBlockSet;
    }

    /**
     * NEW: Expose the mapping from bytecode offset to block ID.
     */
    public Map<Integer, Integer> getOffsetToBlockIdMap() {
        return this.offsetToBlockId;
    }

    /**
     * Get the last instrumented offset (for edge-based instrumentation).
     */
    public Integer getLastInstrumentedOffset() {
        return this.lastInstrumentedOffset;
    }

    /**
     * Initialize the CFG and determine which blocks to instrument using minimal PRS.
     */
    private void initializeCFG(final CtClass ctClass, final MethodInfo methodInfo) {
        // When methodInfo is null (e.g., when called from CoveragePass for class-level granularity),
        // skip CFG initialization as there's no specific method to analyze
        if (methodInfo == null) {
            return;
        }

        try {
            // Build CFG using ControlFlow-based analysis
            this.cfg = CFGBuilder.buildFromControlFlow(ctClass, methodInfo);

            // Get ALL basic blocks using Javassist (same as BasicBlockGranularity)
            ControlFlow cf = new ControlFlow(ctClass, methodInfo);
            int blockId = 0;
            for (Block block : cf.basicBlocks()) {
                int offset = block.position();
                this.blocks.add(offset);
                this.offsetToBlockId.put(offset, blockId);
                blockId++;
            }

            // Find minimal nodes and get simplified edges in one call
            // Use BOTH strategy as it typically provides the best reduction
            this.prsResult = cfg.findMinimalNodesWithEdges(PathRecoveryOrder.BOTH);

            // Mark which blocks are selected (non-removable)
            for (BasicBlockNode node : cfg.nodes.values()) {
                boolean removable = prsResult.removableNodes.contains(node);
                if (!removable) {
                    this.selectedBlockSet.add(node.offset);
                }
            }

            // Build simplified edges using simple version
            List<ControlFlowGraph.SimplifiedEdge> simplifiedEdges =
                cfg.buildSimplifiedEdgesSimple(prsResult, methodInfo);

            // Process the simplified edges
            for (ControlFlowGraph.SimplifiedEdge edge : simplifiedEdges) {
                Pair<Integer, Integer> edgeKey = Pair.of(edge.source.id, edge.destination.id);

                int edgeId = nextEdgeId++;
                edgeLabels.put(edgeKey, edge.label);
                edgeIds.put(edgeKey, edgeId);
                edgePaths.put(edgeKey, edge.path);
            }

        } catch (Exception e) {
            // Fallback to simple block-based instrumentation if CFG analysis fails
            try {
                ControlFlow cf = new ControlFlow(ctClass, methodInfo);
                int blockId = 0;
                for (Block block : cf.basicBlocks()) {
                    this.blocks.add(block.position());
                    this.selectedBlockSet.add(block.position());  // Keep all blocks in fallback mode
                    this.offsetToBlockId.put(block.position(), blockId++);
                }
            } catch (Exception e2) {
                // Ignore secondary exception during fallback
            }
        }
    }

    @Override
    public boolean instrumentAtIndex(final int index, final int instrumentationSize) {
        if (this.blocks.isEmpty()) {
            return false;
        }

        Integer nextBlock = this.blocks.peek();
        if (index >= instrumentationSize + nextBlock) {
            this.lastInstrumentedOffset = this.blocks.poll();
            return selectedBlockSet.contains(this.lastInstrumentedOffset);
        }

        return false;
    }

    @Override
    public boolean stopInstrumenting() {
        return this.blocks.isEmpty();
    }

    @Override
    public String getNodeSuffix() {
        // Add suffix for instrumented blocks (all instrumented blocks are selected by PRS)
        if (lastInstrumentedOffset != null && offsetToBlockId.containsKey(lastInstrumentedOffset)) {
            int blockId = offsetToBlockId.get(lastInstrumentedOffset);
            return "#CFG" + blockId;
        }
        return "";
    }

    /**
     * NEW: Exposes the computed edge-to-ID mapping for CoveragePass.
     */
    @Override
    public Map<Pair<Integer, Integer>, Integer> getEdges() {
        return this.edgeIds;
    }

    /**
     * NEW: Exposes the computed edge-to-label mapping for CoveragePass.
     */
    @Override
    public Map<Pair<Integer, Integer>, String> getEdgeLabels() {
        return this.edgeLabels;
    }

    /**
     * NEW: Exposes the complete path (list of BasicBlockNodes) for each edge.
     * This allows expanding edge-based rankings back to basic block rankings.
     *
     * Note: Paths are expanded on-demand. If a path is null, call expandEdgePath() first.
     */
    public Map<Pair<Integer, Integer>, List<BasicBlockNode>> getEdgePaths() {
        return this.edgePaths;
    }

    /**
     * SelectiveCFG uses edge-based instrumentation.
     */
    @Override
    public boolean isEdgeBased() {
        return true;
    }

    /**
     * Add custom field for edge lookup table.
     * NOTE: Field is actually added by FieldPass, but we keep this for compatibility.
     */
    @Override
    public void addCustomFields(javassist.CtClass ctClass) throws Exception {
        // Do nothing - field is already added by FieldPass
    }

    /**
     * Initialize the edge lookup table in <clinit>.
     * Now simplified to just call EdgeTracker.initializeLookupTable() helper method.
     */
    @Override
    public void initializeCustomFields(javassist.CtClass ctClass,
        Map<Pair<Integer, Integer>, Integer> edgeToProbeIndex) throws Exception {

        if (edgeToProbeIndex == null || edgeToProbeIndex.isEmpty()) {
            return;
        }

        // Find the maximum block ID to determine array size
        int maxBlockId = 0;
        for (Pair<Integer, Integer> edge : edgeToProbeIndex.keySet()) {
            maxBlockId = Math.max(maxBlockId, edge.getLeft());
            maxBlockId = Math.max(maxBlockId, edge.getRight());
        }
        int arraySize = maxBlockId + 1;

        // Get or create <clinit>
        javassist.CtConstructor clinit = ctClass.getClassInitializer();
        if (clinit == null) {
            clinit = ctClass.makeClassInitializer();
        }

        // Build initialization code that directly initializes the lookup table
        // Do NOT use helper method to avoid classloading issues
        StringBuilder code = new StringBuilder();
        code.append("{\n");
        code.append("  __gz_edgeLookupTable = new int[" + arraySize + "][" + arraySize + "];\n");

        // Initialize all cells to -1
        code.append("  for (int __gz_i = 0; __gz_i < " + arraySize + "; __gz_i++) {\n");
        code.append("    for (int __gz_j = 0; __gz_j < " + arraySize + "; __gz_j++) {\n");
        code.append("      __gz_edgeLookupTable[__gz_i][__gz_j] = -1;\n");
        code.append("    }\n");
        code.append("  }\n");

        // Set specific edges
        for (Map.Entry<Pair<Integer, Integer>, Integer> entry : edgeToProbeIndex.entrySet()) {
            int srcId = entry.getKey().getLeft();
            int destId = entry.getKey().getRight();
            int probeIndex = entry.getValue();
            code.append("  __gz_edgeLookupTable[" + srcId + "][" + destId + "] = " + probeIndex + ";\n");
        }

        code.append("}\n");

        // Insert the code at the beginning of <clinit>
        clinit.insertBefore(code.toString());
    }

    /**
     * Generate edge tracking instrumentation code.
     * Simplified version that calls EdgeTracker.trackEdge() helper method.
     *
     * Generated code is equivalent to:
     * EdgeTracker.trackEdge(__gz_edgeLookupTable, __gz$gz$probes,
     *                       CoveragePass.__gz_lastHitNodeId, currentBlockId);
     */
    @Override
    public javassist.bytecode.Bytecode generateCustomInstrumentationCode(
        javassist.CtClass ctClass,
        javassist.bytecode.ConstPool constPool,
        Map<String, Object> context) throws Exception {

        if (context == null || !context.containsKey("blockId")) {
            return null;
        }

        int currentBlockId = (Integer) context.get("blockId");
        javassist.bytecode.Bytecode b = new javassist.bytecode.Bytecode(constPool);

        // Register classes and methods in constant pool
        int edgeTrackerClass = constPool.addClassInfo("com/gzoltar/core/instr/granularity/EdgeTracker");
        int coveragePassClass = constPool.addClassInfo("com/gzoltar/core/instr/pass/CoveragePass");

        // trackEdge(int[][], boolean[], ThreadLocal, int)
        // CRITICAL FIX: Use [Z for boolean[] not [B for byte[]
        int trackEdgeMethod = constPool.addMethodrefInfo(
            edgeTrackerClass,
            "trackEdge",
            "([[I[ZLjava/lang/ThreadLocal;I)V"
        );

        int edgeLookupTableField = constPool.addFieldrefInfo(
            constPool.addClassInfo(ctClass.getName()),
            "__gz_edgeLookupTable",
            "[[I"
        );
        int probesField = constPool.addFieldrefInfo(
            constPool.addClassInfo(ctClass.getName()),
            com.gzoltar.core.instr.InstrumentationConstants.FIELD_NAME,
            com.gzoltar.core.instr.InstrumentationConstants.FIELD_DESC_BYTECODE
        );
        int lastHitNodeField = constPool.addFieldrefInfo(
            coveragePassClass,
            "__gz_lastHitNodeId",
            "Ljava/lang/ThreadLocal;"
        );

        // Push argument 1: __gz_edgeLookupTable
        b.add(javassist.bytecode.Opcode.GETSTATIC);
        b.addIndex(edgeLookupTableField);

        // Push argument 2: __gz$gz$probes
        b.add(javassist.bytecode.Opcode.GETSTATIC);
        b.addIndex(probesField);

        // Push argument 3: CoveragePass.__gz_lastHitNodeId
        b.add(javassist.bytecode.Opcode.GETSTATIC);
        b.addIndex(lastHitNodeField);

        // Push argument 4: currentBlockId
        pushInt(b, currentBlockId);

        // Call EdgeTracker.trackEdge()
        b.add(javassist.bytecode.Opcode.INVOKESTATIC);
        b.addIndex(trackEdgeMethod);

        return b;
    }

    // Helper: Push an integer constant onto the stack
    private void pushInt(javassist.bytecode.Bytecode b, int value) {
        if (value >= -1 && value <= 5) {
            b.addIconst(value);
        } else if (value >= -128 && value <= 127) {
            b.addOpcode(javassist.bytecode.Opcode.BIPUSH);
            b.add(value);
        } else if (value >= -32768 && value <= 32767) {
            b.addOpcode(javassist.bytecode.Opcode.SIPUSH);
            b.add((value >>> 8) & 0xFF);
            b.add(value & 0xFF);
        } else {
            b.addOpcode(javassist.bytecode.Opcode.LDC_W);
            b.addIndex(b.getConstPool().addIntegerInfo(value));
        }
    }
}