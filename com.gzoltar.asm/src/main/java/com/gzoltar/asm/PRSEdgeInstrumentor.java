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
package com.gzoltar.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * PRS (Path Recovery Set) based edge instrumentor.
 *
 * This implements the mPRS algorithm:
 * 1. Build CFG from bytecode
 * 2. Apply PRS algorithm to find removable nodes
 * 3. Only instrument edges between non-removable nodes
 * 4. Record which removed nodes each edge covers for later recovery
 *
 * Key insight: We only probe edges that go through removed nodes.
 * Edges between adjacent non-removed nodes don't need probes.
 */
public class PRSEdgeInstrumentor {

    private static final String PROBE_CLASS = "com/gzoltar/asm/runtime/GlobalProbes";
    private static final String PROBE_FIELD = "probes";
    private static final String PROBE_DESC = "[Z";

    /** All edges that need probing */
    private final List<EdgeRecord> probedEdges = new ArrayList<>();

    /** All basic block nodes (for spectra) */
    private final Set<String> allNodes = new TreeSet<>();

    /** Current probe ID counter */
    private int nextProbeId = 0;

    /** Edge record for recovery */
    public static class EdgeRecord {
        public final int probeId;
        public final String methodKey;
        public final String fromNode;  // source node name
        public final String toNode;    // destination node name
        public final List<String> removedNodes;  // nodes removed between from and to

        public EdgeRecord(int probeId, String methodKey, String fromNode, String toNode, List<String> removedNodes) {
            this.probeId = probeId;
            this.methodKey = methodKey;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.removedNodes = removedNodes;
        }

        @Override
        public String toString() {
            return "Edge#" + probeId + ": " + fromNode + " -> " + toNode +
                   (removedNodes.isEmpty() ? "" : " (removed: " + removedNodes + ")");
        }
    }

    /** Basic block representation */
    private static class BasicBlock {
        final int id;
        final int startIdx;
        int endIdx;
        int lineNumber = -1;  // First line number in block (for compatibility)
        final Set<Integer> allLineNumbers = new HashSet<>();  // ALL line numbers in block
        final Set<Integer> successors = new HashSet<>();
        final Set<Integer> predecessors = new HashSet<>();

        BasicBlock(int id, int startIdx) {
            this.id = id;
            this.startIdx = startIdx;
            this.endIdx = startIdx;
        }
    }

    /**
     * Analyze a class to build CFG and identify PRS edges.
     */
    public void analyzeClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, 0);

        String className = classNode.name.replace('/', '.');

        for (MethodNode method : classNode.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            if (method.name.equals("<clinit>")) {
                continue;
            }

            analyzeMethod(className, method);
        }
    }

    /**
     * Analyze a method: build CFG, apply PRS, identify edges.
     */
    private void analyzeMethod(String className, MethodNode method) {
        String methodKey = className.replace('.', '$') + "#" + method.name + "(" + getParamTypes(method.desc) + ")";

        InsnList insns = method.instructions;
        if (insns.size() == 0) return;

        // Step 0: Collect ALL line numbers as nodes (same as SimpleNodeInstrumentor)
        // This ensures node_spectra.csv matches spectra.csv from Basic Block
        Set<Integer> seenLines = new HashSet<>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof LineNumberNode) {
                int line = ((LineNumberNode) insn).line;
                if (!seenLines.contains(line)) {
                    seenLines.add(line);
                    String nodeName = methodKey + ":" + line;
                    allNodes.add(nodeName);
                }
            }
        }

        // Step 1: Build basic blocks
        List<BasicBlock> blocks = buildBasicBlocks(insns);
        if (blocks.isEmpty()) return;

        // Step 2: Build CFG edges
        buildCFGEdges(blocks, insns);

        // Step 3: Apply PRS algorithm to find removable nodes
        Set<Integer> removableBlocks = findRemovableBlocks(blocks);

        // Step 4: Build simplified edges (only between non-removable blocks)
        // and record which removed blocks each edge covers
        // Pass seenLines so ENTRY edge can record all lines in the method
        buildPRSEdges(blocks, removableBlocks, methodKey, seenLines);
    }

    /**
     * Build basic blocks from instruction list.
     */
    private List<BasicBlock> buildBasicBlocks(InsnList insns) {
        // Find leaders (start of basic blocks)
        Set<Integer> leaders = new TreeSet<>();
        leaders.add(0);  // First instruction

        // Map from label to instruction index
        Map<LabelNode, Integer> labelToIdx = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof LabelNode) {
                labelToIdx.put((LabelNode) insn, i);
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
                // Conditional jump: next instruction is also a leader
                if (insn.getOpcode() != Opcodes.GOTO && i + 1 < insns.size()) {
                    leaders.add(i + 1);
                }
            }
            else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                Integer defIdx = labelToIdx.get(sw.dflt);
                if (defIdx != null) leaders.add(defIdx);
                for (LabelNode label : sw.labels) {
                    Integer idx = labelToIdx.get(label);
                    if (idx != null) leaders.add(idx);
                }
            }
            else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                Integer defIdx = labelToIdx.get(sw.dflt);
                if (defIdx != null) leaders.add(defIdx);
                for (LabelNode label : sw.labels) {
                    Integer idx = labelToIdx.get(label);
                    if (idx != null) leaders.add(idx);
                }
            }
            else if (isReturnOrThrow(insn.getOpcode()) && i + 1 < insns.size()) {
                leaders.add(i + 1);
            }
        }

        // Create basic blocks
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        List<BasicBlock> blocks = new ArrayList<>();

        for (int i = 0; i < sortedLeaders.size(); i++) {
            int startIdx = sortedLeaders.get(i);
            int endIdx = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) - 1 : insns.size() - 1;

            BasicBlock block = new BasicBlock(i, startIdx);
            block.endIdx = endIdx;

            // Find ALL line numbers for this block
            for (int j = startIdx; j <= endIdx && j < insns.size(); j++) {
                AbstractInsnNode insn = insns.get(j);
                if (insn instanceof LineNumberNode) {
                    int line = ((LineNumberNode) insn).line;
                    block.allLineNumbers.add(line);
                    if (block.lineNumber < 0) {
                        block.lineNumber = line;  // Keep first for compatibility
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

        // Map from instruction index to block id
        Map<Integer, Integer> idxToBlock = new HashMap<>();
        for (BasicBlock block : blocks) {
            for (int i = block.startIdx; i <= block.endIdx; i++) {
                idxToBlock.put(i, block.id);
            }
        }

        for (BasicBlock block : blocks) {
            // Find last executable instruction
            AbstractInsnNode lastInsn = null;
            for (int j = block.endIdx; j >= block.startIdx; j--) {
                AbstractInsnNode insn = insns.get(j);
                if (insn.getType() != AbstractInsnNode.LABEL &&
                    insn.getType() != AbstractInsnNode.LINE &&
                    insn.getType() != AbstractInsnNode.FRAME) {
                    lastInsn = insn;
                    break;
                }
            }

            if (lastInsn == null) {
                // Fall through to next block
                if (block.id + 1 < blocks.size()) {
                    block.successors.add(block.id + 1);
                    blocks.get(block.id + 1).predecessors.add(block.id);
                }
                continue;
            }

            if (lastInsn instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) lastInsn;
                Integer targetIdx = labelToIdx.get(jump.label);
                if (targetIdx != null) {
                    Integer targetBlock = idxToBlock.get(targetIdx);
                    if (targetBlock != null) {
                        block.successors.add(targetBlock);
                        blocks.get(targetBlock).predecessors.add(block.id);
                    }
                }
                // Conditional: also fall through
                if (lastInsn.getOpcode() != Opcodes.GOTO && block.id + 1 < blocks.size()) {
                    block.successors.add(block.id + 1);
                    blocks.get(block.id + 1).predecessors.add(block.id);
                }
            }
            else if (lastInsn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) lastInsn;
                Integer defIdx = labelToIdx.get(sw.dflt);
                if (defIdx != null) {
                    Integer defBlock = idxToBlock.get(defIdx);
                    if (defBlock != null) {
                        block.successors.add(defBlock);
                        blocks.get(defBlock).predecessors.add(block.id);
                    }
                }
                for (LabelNode label : sw.labels) {
                    Integer idx = labelToIdx.get(label);
                    if (idx != null) {
                        Integer targetBlock = idxToBlock.get(idx);
                        if (targetBlock != null) {
                            block.successors.add(targetBlock);
                            blocks.get(targetBlock).predecessors.add(block.id);
                        }
                    }
                }
            }
            else if (lastInsn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) lastInsn;
                Integer defIdx = labelToIdx.get(sw.dflt);
                if (defIdx != null) {
                    Integer defBlock = idxToBlock.get(defIdx);
                    if (defBlock != null) {
                        block.successors.add(defBlock);
                        blocks.get(defBlock).predecessors.add(block.id);
                    }
                }
                for (LabelNode label : sw.labels) {
                    Integer idx = labelToIdx.get(label);
                    if (idx != null) {
                        Integer targetBlock = idxToBlock.get(idx);
                        if (targetBlock != null) {
                            block.successors.add(targetBlock);
                            blocks.get(targetBlock).predecessors.add(block.id);
                        }
                    }
                }
            }
            else if (!isReturnOrThrow(lastInsn.getOpcode())) {
                // Fall through
                if (block.id + 1 < blocks.size()) {
                    block.successors.add(block.id + 1);
                    blocks.get(block.id + 1).predecessors.add(block.id);
                }
            }
        }
    }

    /**
     * Apply PRS algorithm to find removable blocks.
     *
     * A block can be removed if:
     * 1. It's not an entry block (in-degree = 0)
     * 2. Removing it doesn't create ambiguity (no edge src->dst already exists)
     * 3. Exit blocks (out-degree = 0) can be removed
     */
    private Set<Integer> findRemovableBlocks(List<BasicBlock> blocks) {
        Set<Integer> removable = new HashSet<>();

        // Working copies of edges
        Map<Integer, Set<Integer>> outEdges = new HashMap<>();
        Map<Integer, Set<Integer>> inEdges = new HashMap<>();

        for (BasicBlock block : blocks) {
            outEdges.put(block.id, new HashSet<>(block.successors));
            inEdges.put(block.id, new HashSet<>(block.predecessors));
        }

        // Find entry block's direct successors - they must be kept
        Set<Integer> entrySuccessors = new HashSet<>();
        for (BasicBlock block : blocks) {
            if (block.predecessors.isEmpty()) {
                entrySuccessors.addAll(block.successors);
            }
        }

        // Sort by in-degree * out-degree (BOTH strategy)
        List<BasicBlock> sortedBlocks = new ArrayList<>(blocks);
        sortedBlocks.sort((a, b) -> {
            int scoreA = a.predecessors.size() * a.successors.size();
            int scoreB = b.predecessors.size() * b.successors.size();
            return Integer.compare(scoreA, scoreB);
        });

        for (BasicBlock block : sortedBlocks) {
            int inDegree = inEdges.get(block.id).size();
            int outDegree = outEdges.get(block.id).size();

            // Entry block (in-degree = 0) - must keep (mPRS rule)
            if (inDegree == 0) {
                continue;
            }

            // Exit block (out-degree = 0) - must keep (mPRS rule)
            // Exit nodes cannot be removed because we need probes there to detect path completion
            if (outDegree == 0) {
                continue;
            }

            // Entry's direct successors - must keep
            if (entrySuccessors.contains(block.id)) {
                continue;
            }

            // Try to remove this block
            boolean canRemove = true;
            Map<Integer, Set<Integer>> newEdges = new HashMap<>();

            for (int pred : inEdges.get(block.id)) {
                for (int succ : outEdges.get(block.id)) {
                    // Check if pred->succ already exists
                    if (outEdges.get(pred).contains(succ)) {
                        canRemove = false;
                        break;
                    }
                    newEdges.computeIfAbsent(pred, k -> new HashSet<>()).add(succ);
                }
                if (!canRemove) break;
            }

            if (canRemove) {
                removable.add(block.id);
                // Add transitive edges
                for (Map.Entry<Integer, Set<Integer>> entry : newEdges.entrySet()) {
                    int pred = entry.getKey();
                    for (int succ : entry.getValue()) {
                        outEdges.get(pred).add(succ);
                        inEdges.get(succ).add(pred);
                    }
                }
            }
        }

        return removable;
    }

    /**
     * Build PRS edges - only between non-removable blocks, recording removed nodes.
     * Also adds ENTRY edge to each method's first block to ensure all code is covered.
     */
    private void buildPRSEdges(List<BasicBlock> blocks, Set<Integer> removable, String methodKey,
                               Set<Integer> methodLineNumbers) {
        // Register all nodes
        for (BasicBlock block : blocks) {
            if (block.lineNumber > 0) {
                String nodeName = methodKey + ":" + block.lineNumber;
                allNodes.add(nodeName);
            }
        }

        // Build simplified edges with removed nodes
        Set<Integer> nonRemovable = new HashSet<>();
        for (BasicBlock block : blocks) {
            if (!removable.contains(block.id)) {
                nonRemovable.add(block.id);
            }
        }

        // ===== ENTRY edge for each method =====
        // The ENTRY edge goes from ENTRY to the first line.
        // For ranking consistency, only include lines from blocks that are ALWAYS executed
        // (i.e., blocks reachable through single-successor paths from entry).
        // Lines inside branches/loops should NOT be in ENTRY's removed_nodes.
        if (!methodLineNumbers.isEmpty()) {
            // Find the first (smallest) line number
            int firstLine = Collections.min(methodLineNumbers);
            String entryNode = methodKey + ":ENTRY";
            String firstNode = methodKey + ":" + firstLine;

            // Trace from entry through single-successor blocks (linear path before any branch)
            List<String> entryRemovedNodes = new ArrayList<>();
            if (!blocks.isEmpty()) {
                Set<Integer> alwaysExecutedLines = new HashSet<>();
                Queue<Integer> queue = new LinkedList<>();
                Set<Integer> visited = new HashSet<>();
                queue.add(0);
                visited.add(0);

                while (!queue.isEmpty()) {
                    int blockId = queue.poll();
                    BasicBlock block = blocks.get(blockId);

                    // Add ALL lines from this block (except firstLine)
                    for (int line : block.allLineNumbers) {
                        if (line != firstLine) {
                            alwaysExecutedLines.add(line);
                        }
                    }

                    // Only continue if single successor (no branch)
                    if (block.successors.size() == 1) {
                        for (int succId : block.successors) {
                            if (!visited.contains(succId)) {
                                visited.add(succId);
                                queue.add(succId);
                            }
                        }
                    }
                    // Stop at branch points or exit points
                }

                for (int line : alwaysExecutedLines) {
                    entryRemovedNodes.add(methodKey + ":" + line);
                }
            }

            // Create ENTRY edge
            EdgeRecord entryEdge = new EdgeRecord(nextProbeId++, methodKey, entryNode, firstNode, entryRemovedNodes);
            probedEdges.add(entryEdge);
        }
        // ===== END ENTRY edge =====

        // For each non-removable block, find paths to other non-removable blocks
        for (int srcId : nonRemovable) {
            BasicBlock srcBlock = blocks.get(srcId);

            // Find source node name - use line number if available, otherwise use block id
            // Blocks without line numbers (e.g., FRAME-only blocks) still need edges for recovery
            String srcNode;
            if (srcBlock.lineNumber > 0) {
                srcNode = methodKey + ":" + srcBlock.lineNumber;
            } else {
                // For blocks without line numbers, we still need to trace edges
                // but we'll use a synthetic node name
                srcNode = methodKey + ":BLOCK" + srcId;
            }

            // BFS to find reachable non-removable blocks
            // Use DFS with stack to handle branching in removable blocks
            for (int succId : srcBlock.successors) {
                // Use stack to handle multiple paths through removable blocks
                Stack<TraceState> stack = new Stack<>();
                stack.push(new TraceState(succId, new ArrayList<>()));

                while (!stack.isEmpty()) {
                    TraceState state = stack.pop();
                    int currentId = state.blockId;
                    List<String> removedNodes = new ArrayList<>(state.removedNodes);

                    // If current is removable, add its lines and continue tracing
                    while (removable.contains(currentId)) {
                        BasicBlock current = blocks.get(currentId);
                        // Add ALL line numbers from this block
                        for (int line : current.allLineNumbers) {
                            removedNodes.add(methodKey + ":" + line);
                        }

                        if (current.successors.isEmpty()) {
                            // Dead end
                            break;
                        } else if (current.successors.size() == 1) {
                            // Single successor - continue linear trace
                            currentId = current.successors.iterator().next();
                        } else {
                            // Multiple successors - push all onto stack for separate traces
                            for (int nextId : current.successors) {
                                stack.push(new TraceState(nextId, new ArrayList<>(removedNodes)));
                            }
                            break;
                        }
                    }

                    // Found destination non-removable block?
                    if (!removable.contains(currentId)) {
                        BasicBlock dstBlock = blocks.get(currentId);
                        if (dstBlock.lineNumber > 0) {
                            String dstNode = methodKey + ":" + dstBlock.lineNumber;

                            // Add other lines in destination block to removed_nodes
                            // This ensures all lines in a block get the same suspiciousness
                            // (they share the same coverage pattern as they're in the same basic block)
                            for (int line : dstBlock.allLineNumbers) {
                                if (line != dstBlock.lineNumber) {
                                    removedNodes.add(methodKey + ":" + line);
                                }
                            }

                            // Only create edge if there are removed nodes OR it's a branch edge
                            if (!removedNodes.isEmpty() || srcBlock.successors.size() > 1) {
                                EdgeRecord edge = new EdgeRecord(nextProbeId++, methodKey, srcNode, dstNode, removedNodes);
                                probedEdges.add(edge);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Helper class for tracking DFS state */
    private static class TraceState {
        final int blockId;
        final List<String> removedNodes;

        TraceState(int blockId, List<String> removedNodes) {
            this.blockId = blockId;
            this.removedNodes = removedNodes;
        }
    }

    /**
     * Instrument a class by inserting probes at PRS edge locations.
     */
    public byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, ClassReader.EXPAND_FRAMES);

        String className = classNode.name.replace('/', '.');

        for (MethodNode method : classNode.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            if (method.name.equals("<clinit>")) {
                continue;
            }

            instrumentMethod(className, method);
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        try {
            classNode.accept(cw);
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[PRSEdgeInstrumentor] Error: " + e.getMessage());
            e.printStackTrace();
            return classBytes;
        }
    }

    /**
     * Instrument method at edge locations.
     */
    private void instrumentMethod(String className, MethodNode method) {
        String methodKey = className.replace('.', '$') + "#" + method.name + "(" + getParamTypes(method.desc) + ")";

        // Find edges for this method
        List<EdgeRecord> methodEdges = new ArrayList<>();
        for (EdgeRecord edge : probedEdges) {
            if (edge.methodKey.equals(methodKey)) {
                methodEdges.add(edge);
            }
        }

        if (methodEdges.isEmpty()) return;

        InsnList insns = method.instructions;

        // Build line number to insertion point map
        Map<Integer, AbstractInsnNode> lineToInsertPoint = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof LineNumberNode) {
                int line = ((LineNumberNode) insn).line;
                // Find first executable instruction after line number
                AbstractInsnNode next = insn.getNext();
                while (next != null &&
                       (next.getType() == AbstractInsnNode.LABEL ||
                        next.getType() == AbstractInsnNode.LINE ||
                        next.getType() == AbstractInsnNode.FRAME)) {
                    next = next.getNext();
                }
                if (next != null) {
                    lineToInsertPoint.put(line, next);
                }
            }
        }

        // Find method entry point (first executable instruction)
        AbstractInsnNode entryInsertPoint = null;
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn.getType() != AbstractInsnNode.LABEL &&
                insn.getType() != AbstractInsnNode.LINE &&
                insn.getType() != AbstractInsnNode.FRAME) {
                entryInsertPoint = insn;
                break;
            }
        }

        // Insert probes at destination blocks
        for (EdgeRecord edge : methodEdges) {
            // Check if this is an ENTRY edge
            if (edge.fromNode.endsWith(":ENTRY")) {
                // Insert probe at method entry
                if (entryInsertPoint != null) {
                    InsnList probeCode = createProbeInstructions(edge.probeId);
                    insns.insertBefore(entryInsertPoint, probeCode);
                }
            } else {
                // Regular edge - insert at destination line
                String toLineSuffix = edge.toNode.substring(edge.toNode.lastIndexOf(':') + 1);
                try {
                    int toLine = Integer.parseInt(toLineSuffix);
                    AbstractInsnNode insertPoint = lineToInsertPoint.get(toLine);
                    if (insertPoint != null) {
                        InsnList probeCode = createProbeInstructions(edge.probeId);
                        insns.insertBefore(insertPoint, probeCode);
                    }
                } catch (NumberFormatException e) {
                    // Skip if can't parse line number
                }
            }
        }
    }

    /**
     * Create instructions to set probeArray[probeId] = true.
     */
    private InsnList createProbeInstructions(int probeId) {
        InsnList insns = new InsnList();

        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, PROBE_CLASS, PROBE_FIELD, PROBE_DESC));

        if (probeId <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + probeId));
        } else if (probeId <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, probeId));
        } else if (probeId <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, probeId));
        } else {
            insns.add(new LdcInsnNode(probeId));
        }

        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.BASTORE));

        return insns;
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
        while (desc.charAt(i) != ')') {
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
                    String className = desc.substring(i + 1, end).replace('/', '.');
                    sb.append(className.substring(className.lastIndexOf('.') + 1));
                    i = end + 1;
                    break;
                case '[':
                    int dims = 0;
                    while (desc.charAt(i) == '[') { dims++; i++; }
                    if (desc.charAt(i) == 'L') {
                        int arrEnd = desc.indexOf(';', i);
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
                default:
                    i++;
            }
        }
        return sb.toString();
    }

    public int getProbeCount() {
        return nextProbeId;
    }

    public List<EdgeRecord> getProbedEdges() {
        return new ArrayList<>(probedEdges);
    }

    public Set<String> getAllNodes() {
        return new TreeSet<>(allNodes);
    }
}
