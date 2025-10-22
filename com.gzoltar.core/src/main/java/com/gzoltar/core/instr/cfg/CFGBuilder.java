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
package com.gzoltar.core.instr.cfg;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.*;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;
import java.util.*;

/**
 * Builds a {@link ControlFlowGraph} from Javassist's ControlFlow analysis.
 */
public class CFGBuilder {
    public static ControlFlowGraph buildFromControlFlow(
            CtClass ctClass,
            MethodInfo methodInfo) throws BadBytecode {

        String methodName = methodInfo.getName();
        ControlFlowGraph graph = new ControlFlowGraph(methodName);

        // use Javassist's ControlFlow to get basic blocks
        ControlFlow cf = new ControlFlow(ctClass, methodInfo);
        Block[] blocks = cf.basicBlocks();

        if (blocks == null || blocks.length == 0) {
            return graph;
        }

        // create a node for each basic block
        for (int i = 0; i < blocks.length; i++) {
            Block block = blocks[i];
            BasicBlockNode node = new BasicBlockNode(
                i,                              // id (sequential)
                block.position(),               // offset (bytecode position)
                methodName + "_block_" + i      // name
            );
            graph.insertNode(node);
        }

        // build edges based on ControlFlow analysis
        for (int i = 0; i < blocks.length; i++) {
            Block block = blocks[i];
            BasicBlockNode fromNode = graph.nodes.get(block.position()); // based on the position, get the from node from our own graph

            if (fromNode == null) {
                continue;
            }

            // add normal control flow edges (exit edges)
            for (int j = 0; j < block.exits(); j++) { // block.exits(): number of exits from the current block
                Block exitBlock = block.exit(j); // get the exit for the current block
                BasicBlockNode toNode = graph.nodes.get(exitBlock.position()); // based on the position, get the to node from our own graph
                if (toNode != null) {
                    graph.insertEdge(fromNode, toNode); // add edge from fromNode to toNode, i.e., from the current block to the exit block
                }
            }
        }

        return graph;
    }
}
