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

import com.gzoltar.core.instr.cfg.BasicBlockNode;
import com.gzoltar.core.instr.cfg.CFGBuilder;
import com.gzoltar.core.instr.cfg.ControlFlowGraph;
import com.gzoltar.core.instr.cfg.PathRecoveryOrder;
import javassist.CtClass;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * A mPRS Node-based granularity that uses the Path Recovery Set (PRS) algorithm
 * to minimize the number of basic blocks that need to be instrumented.
 */
public class MPRSNodeGranularity extends MPRSEdgeGranularity {

    public MPRSNodeGranularity(final CtClass ctClass, final MethodInfo methodInfo) {
        super(ctClass, methodInfo);
    }

    @Override
    protected void initializeCFG(final CtClass ctClass, final MethodInfo methodInfo) {
        super.initializeCFG(ctClass, methodInfo);

        ControlFlowGraph cfg = getCFG();
        if (cfg == null) {
            return;
        }

        Set<BasicBlockNode> removableNodes = cfg.findMinimalNodes(PathRecoveryOrder.BOTH);
        Set<Integer> removableOffsets = new HashSet<>();
        for (BasicBlockNode node : removableNodes) {
            removableOffsets.add(node.offset);
        }

        Set<Integer> selectedOffsets = new HashSet<>();
        for (BasicBlockNode node : cfg.nodes.values()) {
            if (!removableOffsets.contains(node.offset)) {
                selectedOffsets.add(node.offset);
            }
        }
        this.selectedBlockSet = selectedOffsets;
    }

    @Override
    public boolean isEdgeBased() {
        return false;
    }
}
