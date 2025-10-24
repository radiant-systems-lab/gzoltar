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

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;

import com.gzoltar.core.instr.cfg.CFGBuilder;
import com.gzoltar.core.instr.cfg.ControlFlowGraph;
import com.gzoltar.core.instr.cfg.BasicBlockNode;
import com.gzoltar.core.instr.cfg.PathRecoveryOrder;


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

    public SelectiveCFGGranularity(final CtClass ctClass, final MethodInfo methodInfo) {
        super(ctClass, methodInfo);
        initializeCFG(ctClass, methodInfo);
    }

    /**
     * Initialize the CFG and determine which blocks to instrument using minimal PRS.
     */
    private void initializeCFG(final CtClass ctClass, final MethodInfo methodInfo) {
        try {
            // Build CFG using ControlFlow-based analysis
            this.cfg = CFGBuilder.buildFromControlFlow(ctClass, methodInfo);

            // Get ALL basic blocks using Javassist (same as BasicBlockGranularity)
            ControlFlow cf = new ControlFlow(ctClass, methodInfo);
            int blockId = 0;
            for (Block block : cf.basicBlocks()) {
                this.blocks.add(block.position());
                this.offsetToBlockId.put(block.position(), blockId++);
            }

            // Find minimal set of nodes to keep using PRS algorithm
            // Use BOTH strategy as it typically provides the best reduction
            Set<BasicBlockNode> removableNodes = cfg.findMinimalNodes(PathRecoveryOrder.BOTH);

            // Mark which blocks are selected (non-removable)
            for (BasicBlockNode node : cfg.nodes.values()) {
                if (!removableNodes.contains(node)) {
                    this.selectedBlockSet.add(node.offset);
                }
            }

        } catch (Exception e) {
            // Fallback to simple block-based instrumentation if CFG analysis fails
            System.err.println("CFG analysis failed for method " + methodInfo.getName() +
                               ", falling back to basic block instrumentation: " + e.getMessage());
            try {
                ControlFlow cf = new ControlFlow(ctClass, methodInfo);
                int blockId = 0;
                for (Block block : cf.basicBlocks()) {
                    this.blocks.add(block.position());
                    this.selectedBlockSet.add(block.position());  // Keep all blocks in fallback mode
                    this.offsetToBlockId.put(block.position(), blockId++);
                }
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }
    }

    @Override
    public boolean instrumentAtIndex(final int index, final int instrumentationSize) {
        // Instrument ALL blocks (same as BasicBlockGranularity)
        // This ensures coverage data is consistent with BASICBLOCK
        boolean outcome = !this.blocks.isEmpty() && index >= instrumentationSize + this.blocks.peek();
        if (outcome) {
            this.lastInstrumentedOffset = this.blocks.poll();
        }
        return outcome;
    }

    @Override
    public boolean stopInstrumenting() {
        return this.blocks.isEmpty();
    }

    @Override
    public String getNodeSuffix() {
        // Only add suffix for blocks selected by PRS algorithm
        // Non-selected blocks will be filtered out later
        if (lastInstrumentedOffset != null && selectedBlockSet.contains(lastInstrumentedOffset)
            && offsetToBlockId.containsKey(lastInstrumentedOffset)) {
            int blockId = offsetToBlockId.get(lastInstrumentedOffset);
            return "#CFG" + blockId;
        }
        // Return a special suffix for non-selected blocks so they can be filtered out
        return "#SKIP";
    }

    /**
     * Get statistics about the CFG and instrumentation decisions.
     */
    public String getStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("CFG Statistics for ").append(methodInfo.getName()).append(":\n");
        stats.append("  Total basic blocks: ").append(cfg != null ? cfg.nodes.size() : 0).append("\n");
        stats.append("  Blocks to instrument: ").append(selectedBlockSet.size()).append("\n");

        if (cfg != null && cfg.nodes.size() > 0) {
            double reduction = (1.0 - (double) selectedBlockSet.size() / cfg.nodes.size()) * 100;
            stats.append("  Reduction: ").append(String.format("%.1f%%", reduction)).append("\n");
        }

        return stats.toString();
    }
}