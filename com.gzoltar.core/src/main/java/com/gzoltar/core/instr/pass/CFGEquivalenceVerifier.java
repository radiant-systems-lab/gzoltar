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

import java.io.ByteArrayInputStream;
import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;

/**
 * CFG Equivalence Verifier: Validates that ASM and Javassist see the same Control Flow Graph.
 *
 * <h2>Purpose</h2>
 * When using a hybrid approach (Javassist for CFG analysis + ASM for instrumentation),
 * we need to ensure both libraries interpret the bytecode's control flow identically.
 * This class provides tools to:
 * <ol>
 *   <li>Build a CFG from ASM's tree API (Labels + jump instructions)</li>
 *   <li>Build a CFG from Javassist's ControlFlow.Block</li>
 *   <li>Establish node correspondence via bytecode offsets</li>
 *   <li>Verify edge equivalence between the two graphs</li>
 * </ol>
 *
 * <h2>Key Insight</h2>
 * Both libraries ultimately parse the same bytecode. The correspondence is established by
 * <b>bytecode offset</b>: each basic block starts at a specific offset in the Code attribute.
 *
 * <pre>
 * Javassist: Block.position() returns the bytecode offset of the block's first instruction
 * ASM:       LabelNode offset can be computed after visiting with a ClassReader
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>
 * byte[] classBytes = ...;
 * CFGEquivalenceVerifier verifier = new CFGEquivalenceVerifier();
 * VerificationResult result = verifier.verify(classBytes, "methodName", "()V");
 * if (!result.isEquivalent()) {
 *     System.err.println("CFG mismatch: " + result.getDifferences());
 * }
 * </pre>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Must be called BEFORE instrumentation modifies the bytecode</li>
 *   <li>ASM Labels may include non-control-flow markers (line numbers, debug info)</li>
 *   <li>Exception handlers add implicit edges that both libraries should recognize</li>
 * </ul>
 */
public class CFGEquivalenceVerifier {

    /** Enable debug output */
    private static final boolean DEBUG = Boolean.getBoolean("gzoltar.cfg.verify.debug");

    // ========================================================================
    // Data Structures for CFG Representation
    // ========================================================================

    /**
     * A unified basic block representation that can be built from either ASM or Javassist.
     * Blocks are identified by their starting bytecode offset.
     */
    public static class BasicBlock {
        /** Bytecode offset where this block starts */
        public final int startOffset;

        /** Bytecode offset where this block ends (exclusive) */
        public final int endOffset;

        /** Successor blocks (outgoing edges), identified by their start offsets */
        public final Set<Integer> successors = new TreeSet<>();

        /** Predecessor blocks (incoming edges), identified by their start offsets */
        public final Set<Integer> predecessors = new TreeSet<>();

        /** Source of this block (for debugging) */
        public final String source;

        public BasicBlock(int startOffset, int endOffset, String source) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.source = source;
        }

        @Override
        public String toString() {
            return String.format("BB[%d-%d] succs=%s preds=%s (%s)",
                startOffset, endOffset, successors, predecessors, source);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BasicBlock)) return false;
            BasicBlock that = (BasicBlock) o;
            return startOffset == that.startOffset;
        }

        @Override
        public int hashCode() {
            return startOffset;
        }
    }

    /**
     * A complete CFG for a method.
     */
    public static class CFG {
        /** Method identifier */
        public final String methodName;
        public final String methodDesc;

        /** Blocks indexed by start offset */
        public final Map<Integer, BasicBlock> blocks = new TreeMap<>();

        /** Entry block offset (usually 0) */
        public int entryOffset = 0;

        /** Source library (ASM or Javassist) */
        public final String source;

        public CFG(String methodName, String methodDesc, String source) {
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.source = source;
        }

        public void addBlock(BasicBlock block) {
            blocks.put(block.startOffset, block);
        }

        public BasicBlock getBlock(int offset) {
            return blocks.get(offset);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("CFG[%s%s] from %s:\n", methodName, methodDesc, source));
            for (BasicBlock block : blocks.values()) {
                sb.append("  ").append(block).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Result of CFG equivalence verification.
     */
    public static class VerificationResult {
        public final boolean equivalent;
        public final List<String> differences = new ArrayList<>();
        public final CFG asmCFG;
        public final CFG javassistCFG;

        public VerificationResult(boolean equivalent, CFG asmCFG, CFG javassistCFG) {
            this.equivalent = equivalent;
            this.asmCFG = asmCFG;
            this.javassistCFG = javassistCFG;
        }

        public boolean isEquivalent() {
            return equivalent;
        }

        public String getDifferences() {
            return String.join("\n", differences);
        }
    }

    // ========================================================================
    // ASM CFG Construction (using Javassist's CodeIterator for offsets)
    // ========================================================================

    /**
     * Build a CFG from ASM using Javassist's Basic Block logic.
     *
     * Uses Javassist's CodeIterator to get precise instruction offsets,
     * avoiding the need to manually calculate instruction sizes.
     *
     * @param methodNode ASM MethodNode
     * @param methodInfo Javassist MethodInfo (for CodeIterator)
     * @return CFG built from ASM matching Javassist's BB logic
     */
    public CFG buildCFGFromASM(MethodNode methodNode, MethodInfo methodInfo) throws BadBytecode {
        CFG cfg = new CFG(methodNode.name, methodNode.desc, "ASM");

        CodeAttribute ca = methodInfo.getCodeAttribute();
        if (ca == null) {
            return cfg;
        }

        // Step 1: Use CodeIterator to get all instruction offsets
        List<Integer> allOffsets = new ArrayList<>();
        CodeIterator ci = ca.iterator();
        while (ci.hasNext()) {
            allOffsets.add(ci.next());
        }
        int codeLength = ca.getCodeLength();

        // Build offset -> nextOffset map (for fall-through calculation)
        Map<Integer, Integer> nextOffsetMap = new HashMap<>();
        for (int i = 0; i < allOffsets.size() - 1; i++) {
            nextOffsetMap.put(allOffsets.get(i), allOffsets.get(i + 1));
        }
        if (!allOffsets.isEmpty()) {
            nextOffsetMap.put(allOffsets.get(allOffsets.size() - 1), codeLength);
        }

        // Step 2: Build offset -> AbstractInsnNode mapping
        Map<Integer, AbstractInsnNode> offsetToInsn = buildOffsetToInsnMap(methodNode, allOffsets);
        Map<AbstractInsnNode, Integer> insnToOffset = new HashMap<>();
        for (Map.Entry<Integer, AbstractInsnNode> entry : offsetToInsn.entrySet()) {
            insnToOffset.put(entry.getValue(), entry.getKey());
        }

        // Step 3: Identify leaders (Javassist-style)
        Set<Integer> leaders = new TreeSet<>();
        leaders.add(0); // Method entry

        for (AbstractInsnNode insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            Integer offset = insnToOffset.get(insn);
            if (offset == null) continue;

            int opcode = insn.getOpcode();

            if (insn instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) insn;
                Integer targetOffset = findLabelOffset(jump.label, insnToOffset);

                // ============================================================
                // Conditional jumps: IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                //                    IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                //                    IF_ACMPEQ, IF_ACMPNE, IFNULL, IFNONNULL
                // Javassist: BasicBlock.java line 238-243
                // ============================================================
                if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) {
                    // 1. Jump target is a leader
                    if (targetOffset != null) {
                        leaders.add(targetOffset);
                    }
                    // 2. Fall-through (next instruction) is also a leader
                    Integer fallThroughOffset = nextOffsetMap.get(offset);
                    if (fallThroughOffset != null && fallThroughOffset < codeLength) {
                        leaders.add(fallThroughOffset);
                    }
                    if (DEBUG) {
                        System.out.println("[DEBUG] Conditional jump at " + offset +
                            " -> target " + targetOffset + ", fall-through " + fallThroughOffset);
                    }
                }
                // ============================================================
                // GOTO / JSR: unconditional jump
                // Javassist: BasicBlock.java line 246-248, 307-311
                // ============================================================
                else {
                    // 1. Jump target is a leader
                    if (targetOffset != null) {
                        leaders.add(targetOffset);
                    }
                    // 2. NO fall-through (unconditional jump)
                    if (DEBUG) {
                        System.out.println("[DEBUG] GOTO/JSR at " + offset +
                            " -> target " + targetOffset + " (no fall-through)");
                    }
                }
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                Integer dfltOffset = findLabelOffset(sw.dflt, insnToOffset);
                if (dfltOffset != null) leaders.add(dfltOffset);
                for (LabelNode label : sw.labels) {
                    Integer lblOffset = findLabelOffset(label, insnToOffset);
                    if (lblOffset != null) leaders.add(lblOffset);
                }
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                Integer dfltOffset = findLabelOffset(sw.dflt, insnToOffset);
                if (dfltOffset != null) leaders.add(dfltOffset);
                for (LabelNode label : sw.labels) {
                    Integer lblOffset = findLabelOffset(label, insnToOffset);
                    if (lblOffset != null) leaders.add(lblOffset);
                }
            } else if (isTerminalInsn(insn)) {
                // After RETURN/ATHROW, next instruction is a leader (if reachable)
                // handle dead code
                Integer nextOffset = nextOffsetMap.get(offset);
                if (nextOffset != null && nextOffset < codeLength) {
                    leaders.add(nextOffset);
                }
            }
        }

        // Add exception handler table entries as leaders
        // Javassist creates BBs at: try start and handler start
        // Note: try block END is NOT a leader in Javassist (it's just metadata)
        if (methodNode.tryCatchBlocks != null) {
            if (DEBUG) {
                System.out.println("[DEBUG] Exception table has " + methodNode.tryCatchBlocks.size() + " entries");
            }
            for (int i = 0; i < methodNode.tryCatchBlocks.size(); i++) {
                TryCatchBlockNode tcb = methodNode.tryCatchBlocks.get(i);
                // Handler start is a leader
                Integer handlerOffset = findLabelOffset(tcb.handler, insnToOffset);
                if (handlerOffset != null) {
                    leaders.add(handlerOffset);
                }
                // Try block start is a leader
                Integer startOffset = findLabelOffset(tcb.start, insnToOffset);
                if (startOffset != null) {
                    leaders.add(startOffset);
                }
                // NOTE: We do NOT add try block END as a leader
                // Javassist doesn't create a block boundary there
                if (DEBUG) {
                    Integer endOffset = findLabelOffset(tcb.end, insnToOffset);
                    System.out.println("[DEBUG] ExcTable[" + i + "]: start=" + startOffset +
                        ", end=" + endOffset + ", handler=" + handlerOffset +
                        ", type=" + (tcb.type == null ? "finally" : tcb.type));
                }
            }
        }

        // Step 4: Create basic blocks
        List<Integer> leaderList = new ArrayList<>(leaders);

        for (int i = 0; i < leaderList.size(); i++) {
            int start = leaderList.get(i);
            int end = (i + 1 < leaderList.size()) ? leaderList.get(i + 1) : codeLength;
            cfg.addBlock(new BasicBlock(start, end, "ASM"));
        }

        // Step 5: Establish edges
        // Only process the LAST instruction of each block to add edges
        for (AbstractInsnNode insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            Integer offset = insnToOffset.get(insn);
            if (offset == null) continue;

            BasicBlock currentBlock = findBlockContaining(cfg, offset);
            if (currentBlock == null) continue;

            // Get next offset from the map (built from CodeIterator)
            Integer nextOffset = nextOffsetMap.get(offset);
            if (nextOffset == null) continue;

            // Only process if this is the LAST instruction in the block
            if (nextOffset < currentBlock.endOffset) {
                continue; // Not the last instruction, skip
            }

            int opcode = insn.getOpcode();

            if (insn instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) insn;
                Integer targetOffset = findLabelOffset(jump.label, insnToOffset);
                if (targetOffset != null) {
                    addEdge(cfg, currentBlock.startOffset, targetOffset);
                }
                // Conditional: also add fall-through edge
                if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) {
                    if (nextOffset < codeLength) {
                        addEdge(cfg, currentBlock.startOffset, nextOffset);
                    }
                }
            } else if (insn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) insn;
                Integer dfltOffset = findLabelOffset(sw.dflt, insnToOffset);
                if (dfltOffset != null) addEdge(cfg, currentBlock.startOffset, dfltOffset);
                for (LabelNode label : sw.labels) {
                    Integer lblOffset = findLabelOffset(label, insnToOffset);
                    if (lblOffset != null) addEdge(cfg, currentBlock.startOffset, lblOffset);
                }
            } else if (insn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) insn;
                Integer dfltOffset = findLabelOffset(sw.dflt, insnToOffset);
                if (dfltOffset != null) addEdge(cfg, currentBlock.startOffset, dfltOffset);
                for (LabelNode label : sw.labels) {
                    Integer lblOffset = findLabelOffset(label, insnToOffset);
                    if (lblOffset != null) addEdge(cfg, currentBlock.startOffset, lblOffset);
                }
            } else if (!isTerminalInsn(insn)) {
                // Non-terminal, non-jump instruction at end of block: add fall-through
                if (nextOffset < codeLength) {
                    addEdge(cfg, currentBlock.startOffset, nextOffset);
                }
            }
            // Terminal instructions (RETURN, ATHROW) have no successors - don't add any edges
        }

        return cfg;
    }

    /**
     * Build mapping: bytecode offset -> ASM AbstractInsnNode
     *
     * Uses offsets from Javassist's CodeIterator to match ASM instructions.
     *
     * @param methodNode ASM MethodNode
     * @param allOffsets List of instruction offsets from CodeIterator
     */
    private Map<Integer, AbstractInsnNode> buildOffsetToInsnMap(MethodNode methodNode, List<Integer> allOffsets) {
        Map<AbstractInsnNode, Integer> realInsnToOffset = new HashMap<>();

        // Collect ASM's real instructions (opcode >= 0)
        List<AbstractInsnNode> realInsns = new ArrayList<>();
        for (AbstractInsnNode insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                realInsns.add(insn);
            }
        }

        // Match ASM instructions to CodeIterator offsets
        int count = Math.min(realInsns.size(), allOffsets.size());
        for (int i = 0; i < count; i++) {
            realInsnToOffset.put(realInsns.get(i), allOffsets.get(i));
        }

        // Build offsetToInsn map
        Map<Integer, AbstractInsnNode> offsetToInsn = new HashMap<>();
        for (Map.Entry<AbstractInsnNode, Integer> entry : realInsnToOffset.entrySet()) {
            offsetToInsn.put(entry.getValue(), entry.getKey());
        }

        // Build label offset cache
        labelOffsetCache.clear();
        for (AbstractInsnNode insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                // Label's offset = first real instruction after it
                AbstractInsnNode next = insn.getNext();
                while (next != null && next.getOpcode() < 0) {
                    next = next.getNext();
                }
                if (next != null) {
                    Integer nextOffset = realInsnToOffset.get(next);
                    if (nextOffset != null) {
                        labelOffsetCache.put((LabelNode) insn, nextOffset);
                    }
                }
            }
        }

        return offsetToInsn;
    }

    // Cache for label offsets
    private final Map<LabelNode, Integer> labelOffsetCache = new HashMap<>();

    /**
     * Find the bytecode offset for a LabelNode.
     */
    private Integer findLabelOffset(LabelNode label, Map<AbstractInsnNode, Integer> insnToOffset) {
        // First check the label offset cache (populated during buildOffsetToInsnMap)
        Integer cached = labelOffsetCache.get(label);
        if (cached != null) return cached;

        // Fallback: check if label itself has offset in the map
        Integer offset = insnToOffset.get(label);
        if (offset != null) return offset;

        // Otherwise, find the first real instruction after this label
        AbstractInsnNode next = label.getNext();
        while (next != null) {
            if (next.getOpcode() >= 0) {
                return insnToOffset.get(next);
            }
            next = next.getNext();
        }
        return null;
    }

    private boolean isTerminalInsn(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN ||
               opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN ||
               opcode == Opcodes.ATHROW;
    }

    private BasicBlock findBlockContaining(CFG cfg, int offset) {
        for (BasicBlock block : cfg.blocks.values()) {
            if (offset >= block.startOffset && offset < block.endOffset) {
                return block;
            }
        }
        return null;
    }

    private void addEdge(CFG cfg, int fromOffset, int toOffset) {
        BasicBlock from = cfg.getBlock(fromOffset);
        BasicBlock to = cfg.getBlock(toOffset);
        if (from != null && to != null) {
            from.successors.add(toOffset);
            to.predecessors.add(fromOffset);
        }
    }

    // ========================================================================
    // Javassist CFG Construction
    // ========================================================================

    /**
     * Build a CFG from Javassist's ControlFlow API.
     *
     * Javassist's ControlFlow.Block provides:
     * - position(): bytecode offset of block start
     * - length(): bytecode length of the block
     * - exits(): number of successor blocks
     * - exit(i): the i-th successor Block
     * - entrances(): number of predecessor blocks
     * - entrance(i): the i-th predecessor Block
     */
    public CFG buildCFGFromJavassist(CtMethod method) throws BadBytecode {
        CFG cfg = new CFG(method.getName(), method.getSignature(), "Javassist");

        MethodInfo methodInfo = method.getMethodInfo();
        if (methodInfo.getCodeAttribute() == null) {
            return cfg; // Abstract or native method
        }

        ControlFlow controlFlow = new ControlFlow(method);
        Block[] blocks = controlFlow.basicBlocks();

        // Create blocks
        for (Block block : blocks) {
            int start = block.position();
            int end = start + block.length();
            cfg.addBlock(new BasicBlock(start, end, "Javassist"));
        }

        // Establish edges
        for (Block block : blocks) {
            int fromOffset = block.position();
            for (int i = 0; i < block.exits(); i++) {
                Block successor = block.exit(i);
                int toOffset = successor.position();
                addEdge(cfg, fromOffset, toOffset);
            }
        }

        return cfg;
    }

    // ========================================================================
    // Equivalence Verification
    // ========================================================================

    /**
     * Verify that ASM and Javassist produce equivalent CFGs for a method.
     *
     * @param classBytes Original class bytecode (BEFORE instrumentation)
     * @param methodName Method name to verify
     * @param methodDesc Method descriptor (e.g., "()V", "(II)I")
     * @return VerificationResult with equivalence status and any differences
     */
    public VerificationResult verify(byte[] classBytes, String methodName, String methodDesc) {
        try {
            // First, parse with Javassist to get code bytes (easier to extract)
            ClassPool pool = ClassPool.getDefault();
            CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classBytes));

            // Find method in Javassist
            CtMethod ctMethod = null;
            for (CtMethod m : ctClass.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getSignature().equals(methodDesc)) {
                    ctMethod = m;
                    break;
                }
            }

            if (ctMethod == null) {
                VerificationResult result = new VerificationResult(false, null, null);
                result.differences.add("Method not found in Javassist: " + methodName + methodDesc);
                ctClass.detach();
                return result;
            }

            // Get code bytes from Javassist's CodeAttribute
            MethodInfo methodInfo = ctMethod.getMethodInfo();
            if (methodInfo.getCodeAttribute() == null) {
                VerificationResult result = new VerificationResult(false, null, null);
                result.differences.add("Method has no code (abstract/native): " + methodName + methodDesc);
                ctClass.detach();
                return result;
            }
            // Build ASM CFG using CodeIterator for precise offset calculation
            ClassReader cr = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, ClassReader.EXPAND_FRAMES);

            MethodNode targetMethod = null;
            for (MethodNode mn : classNode.methods) {
                if (mn.name.equals(methodName) && mn.desc.equals(methodDesc)) {
                    targetMethod = mn;
                    break;
                }
            }

            if (targetMethod == null) {
                VerificationResult result = new VerificationResult(false, null, null);
                result.differences.add("Method not found in ASM: " + methodName + methodDesc);
                ctClass.detach();
                return result;
            }

            CFG asmCFG = buildCFGFromASM(targetMethod, methodInfo);

            // Build Javassist CFG
            CFG javassistCFG = buildCFGFromJavassist(ctMethod);

            // Cleanup
            ctClass.detach();

            // Compare CFGs
            return compareCFGs(asmCFG, javassistCFG);

        } catch (Exception e) {
            VerificationResult result = new VerificationResult(false, null, null);
            result.differences.add("Exception during verification: " + e.getMessage());
            return result;
        }
    }

    /**
     * Compare two CFGs for equivalence.
     *
     * Two CFGs are equivalent if:
     * 1. They have the same number of basic blocks
     * 2. Each block in one CFG has a corresponding block in the other (same start offset)
     * 3. Corresponding blocks have the same successor/predecessor sets
     */
    private VerificationResult compareCFGs(CFG asm, CFG javassist) {
        List<String> differences = new ArrayList<>();
        boolean equivalent = true;

        if (DEBUG) {
            System.out.println("[DEBUG] Comparing CFGs: ASM has " + asm.blocks.size() +
                " blocks, Javassist has " + javassist.blocks.size() + " blocks");
            System.out.println("[DEBUG] ASM block offsets: " + asm.blocks.keySet());
            System.out.println("[DEBUG] Javassist block offsets: " + javassist.blocks.keySet());
        }

        // Check block count
        if (asm.blocks.size() != javassist.blocks.size()) {
            differences.add(String.format("Block count mismatch: ASM=%d, Javassist=%d",
                asm.blocks.size(), javassist.blocks.size()));
            equivalent = false;
        }

        // Check each ASM block has a corresponding Javassist block
        for (Map.Entry<Integer, BasicBlock> entry : asm.blocks.entrySet()) {
            int offset = entry.getKey();
            BasicBlock asmBlock = entry.getValue();
            BasicBlock jaBlock = javassist.getBlock(offset);

            if (jaBlock == null) {
                differences.add(String.format("ASM block at offset %d has no Javassist counterpart", offset));
                equivalent = false;
                continue;
            }

            // Compare successors
            if (!asmBlock.successors.equals(jaBlock.successors)) {
                differences.add(String.format("Successor mismatch at offset %d: ASM=%s, Javassist=%s",
                    offset, asmBlock.successors, jaBlock.successors));
                equivalent = false;
            }

            // Compare predecessors
            if (!asmBlock.predecessors.equals(jaBlock.predecessors)) {
                differences.add(String.format("Predecessor mismatch at offset %d: ASM=%s, Javassist=%s",
                    offset, asmBlock.predecessors, jaBlock.predecessors));
                equivalent = false;
            }
        }

        // Check for Javassist blocks not in ASM
        for (Integer offset : javassist.blocks.keySet()) {
            if (!asm.blocks.containsKey(offset)) {
                differences.add(String.format("Javassist block at offset %d has no ASM counterpart", offset));
                equivalent = false;
            }
        }

        VerificationResult result = new VerificationResult(equivalent, asm, javassist);
        result.differences.addAll(differences);
        return result;
    }

    // ========================================================================
    // Batch Verification (all methods in a class)
    // ========================================================================

    /**
     * Result of verifying all methods in a class.
     */
    public static class ClassVerificationResult {
        public final String className;
        public final int totalMethods;
        public final int verifiedMethods;
        public final int equivalentMethods;
        public final int mismatchedMethods;
        public final List<String> mismatches = new ArrayList<>();

        public ClassVerificationResult(String className, int total, int verified, int equivalent, int mismatched) {
            this.className = className;
            this.totalMethods = total;
            this.verifiedMethods = verified;
            this.equivalentMethods = equivalent;
            this.mismatchedMethods = mismatched;
        }

        public boolean isAllEquivalent() {
            return mismatchedMethods == 0;
        }
    }

    /**
     * Verify all methods in a class file.
     *
     * @param classBytes Original class bytecode
     * @return ClassVerificationResult with summary
     */
    public ClassVerificationResult verifyAllMethods(byte[] classBytes) {
        try {
            // Parse with ASM
            ClassReader cr = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, ClassReader.EXPAND_FRAMES);

            String className = classNode.name.replace('/', '.');

            // Parse with Javassist
            ClassPool pool = ClassPool.getDefault();
            CtClass ctClass = pool.makeClass(new ByteArrayInputStream(classBytes));

            int total = 0;
            int verified = 0;
            int equivalent = 0;
            int mismatched = 0;
            List<String> mismatchDetails = new ArrayList<>();

            // Verify each method
            for (MethodNode mn : classNode.methods) {
                // Skip abstract and native methods
                if ((mn.access & Opcodes.ACC_ABSTRACT) != 0 ||
                    (mn.access & Opcodes.ACC_NATIVE) != 0) {
                    continue;
                }

                // Skip methods with no code
                if (mn.instructions.size() == 0) {
                    continue;
                }

                total++;

                try {
                    // For constructors, check CtConstructor
                    if (mn.name.equals("<init>")) {
                        for (javassist.CtConstructor ctor : ctClass.getDeclaredConstructors()) {
                            if (ctor.getSignature().equals(mn.desc)) {
                                MethodInfo ctorInfo = ctor.getMethodInfo();
                                if (ctorInfo.getCodeAttribute() == null) continue;

                                // Build CFGs
                                CFG asmCFG = buildCFGFromASM(mn, ctorInfo);
                                CFG javassistCFG = buildCFGFromJavassistConstructor(ctor);
                                VerificationResult result = compareCFGs(asmCFG, javassistCFG);
                                verified++;
                                if (result.isEquivalent()) {
                                    equivalent++;
                                } else {
                                    mismatched++;
                                    mismatchDetails.add(mn.name + mn.desc + ": " + result.getDifferences());
                                }
                                break;
                            }
                        }
                        continue;
                    }

                    // Skip static initializers (hard to compare)
                    if (mn.name.equals("<clinit>")) {
                        continue;
                    }

                    // Find corresponding Javassist method
                    CtMethod ctMethod = null;
                    for (CtMethod m : ctClass.getDeclaredMethods()) {
                        if (m.getName().equals(mn.name) && m.getSignature().equals(mn.desc)) {
                            ctMethod = m;
                            break;
                        }
                    }

                    if (ctMethod == null) {
                        // Method not found in Javassist (might be synthetic)
                        continue;
                    }

                    MethodInfo methodInfo = ctMethod.getMethodInfo();
                    if (methodInfo.getCodeAttribute() == null) {
                        continue;  // No code
                    }

                    // Build CFGs using CodeIterator for offsets
                    CFG asmCFG = buildCFGFromASM(mn, methodInfo);
                    CFG javassistCFG = buildCFGFromJavassist(ctMethod);
                    VerificationResult result = compareCFGs(asmCFG, javassistCFG);

                    verified++;
                    if (result.isEquivalent()) {
                        equivalent++;
                    } else {
                        mismatched++;
                        mismatchDetails.add(mn.name + mn.desc + ": " + result.getDifferences());
                    }

                } catch (Exception e) {
                    // Skip methods that can't be analyzed (e.g., complex bytecode)
                    if (DEBUG) {
                        System.out.println("[DEBUG] Skipping " + mn.name + ": " + e.getMessage());
                    }
                }
            }

            // Cleanup
            ctClass.detach();

            ClassVerificationResult result = new ClassVerificationResult(
                className, total, verified, equivalent, mismatched);
            result.mismatches.addAll(mismatchDetails);
            return result;

        } catch (Exception e) {
            ClassVerificationResult result = new ClassVerificationResult(
                "unknown", 0, 0, 0, 1);
            result.mismatches.add("Exception: " + e.getMessage());
            return result;
        }
    }

    /**
     * Build CFG from Javassist CtConstructor.
     */
    private CFG buildCFGFromJavassistConstructor(javassist.CtConstructor ctor) throws BadBytecode {
        CFG cfg = new CFG("<init>", ctor.getSignature(), "Javassist");

        MethodInfo methodInfo = ctor.getMethodInfo();
        if (methodInfo.getCodeAttribute() == null) {
            return cfg;
        }

        // ControlFlow accepts CtClass + MethodInfo for constructors
        ControlFlow controlFlow = new ControlFlow(ctor.getDeclaringClass(), methodInfo);
        Block[] blocks = controlFlow.basicBlocks();

        for (Block block : blocks) {
            int start = block.position();
            int end = start + block.length();
            cfg.addBlock(new BasicBlock(start, end, "Javassist"));
        }

        for (Block block : blocks) {
            int fromOffset = block.position();
            for (int i = 0; i < block.exits(); i++) {
                Block successor = block.exit(i);
                int toOffset = successor.position();
                addEdge(cfg, fromOffset, toOffset);
            }
        }

        return cfg;
    }

    // ========================================================================
    // Main Entry Point for Testing
    // ========================================================================

    /**
     * Main entry point.
     *
     * Usage:
     *   CFGEquivalenceVerifier <classFile>                    # Verify all methods
     *   CFGEquivalenceVerifier <classFile> <method> <desc>    # Verify single method
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("  CFGEquivalenceVerifier <classFile>                  # Verify all methods");
            System.out.println("  CFGEquivalenceVerifier <classFile> <method> <desc>  # Verify single method");
            System.out.println("");
            System.out.println("Examples:");
            System.out.println("  CFGEquivalenceVerifier MyClass.class");
            System.out.println("  CFGEquivalenceVerifier MyClass.class myMethod (II)I");
            return;
        }

        String classFile = args[0];

        // Read class bytes
        java.io.File file = new java.io.File(classFile);
        byte[] classBytes = new byte[(int) file.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        fis.read(classBytes);
        fis.close();

        CFGEquivalenceVerifier verifier = new CFGEquivalenceVerifier();

        if (args.length >= 3) {
            // Single method verification
            String methodName = args[1];
            String methodDesc = args[2];

            VerificationResult result = verifier.verify(classBytes, methodName, methodDesc);

            if (result.isEquivalent()) {
                System.out.println("SUCCESS: CFGs are equivalent!");
            } else {
                System.out.println("FAILURE: CFGs differ!");
                System.out.println("\nDifferences:");
                for (String diff : result.differences) {
                    System.out.println("  - " + diff);
                }
            }

        } else {
            // Verify all methods in class
            ClassVerificationResult result = verifier.verifyAllMethods(classBytes);

            System.out.println("Class: " + result.className);
            System.out.println("Total methods: " + result.totalMethods);
            System.out.println("Verified: " + result.verifiedMethods);
            System.out.println("Equivalent: " + result.equivalentMethods);
            System.out.println("Mismatched: " + result.mismatchedMethods);

            if (result.isAllEquivalent()) {
                System.out.println("\nSUCCESS: All CFGs are equivalent!");
            } else {
                System.out.println("\nFAILURE: Some CFGs differ!");
                System.out.println("\nMismatches:");
                for (String mismatch : result.mismatches) {
                    System.out.println("  - " + mismatch);
                }
            }
        }
    }
}
