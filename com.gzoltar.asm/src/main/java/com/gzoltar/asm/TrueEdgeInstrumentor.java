/**
 * Copyright (C) 2020 GZoltar contributors.
 *
 * This file is part of GZoltar.
 */
package com.gzoltar.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * PRS Edge Instrumentor - 使用PRS算法进行边覆盖插装
 *
 * ============== 算法流程 ==============
 *
 * 以 App.java 的 mid() 方法为例：
 *
 *     public int mid(int x, int y, int z) {
 *         int m = z;                    // Line 5
 *         if (y > z) {                  // Line 6 (bug: should be y < z)
 *             if (x < y)                // Line 7
 *                 m = y;                // Line 8
 *             else if (x < z)           // Line 9
 *                 m = x;                // Line 10
 *         } else {                      // Line 11
 *             if (x > y)                // Line 12
 *                 m = y;                // Line 13
 *             else if (x > z)           // Line 14
 *                 m = x;                // Line 15
 *         }
 *         return m;                     // Line 17
 *     }
 *
 * Step 1: 构建基本块 (Basic Blocks)
 * ─────────────────────────────────
 *     Block 0: Line 5,6  (ENTRY, m=z, if y>z)
 *     Block 1: Line 7    (if x<y)
 *     Block 2: Line 8    (m=y, goto 17)
 *     Block 3: Line 9    (if x<z)
 *     Block 4: Line 10   (m=x, goto 17)
 *     Block 5: Line 12   (if x>y)
 *     Block 6: Line 13   (m=y, goto 17)
 *     Block 7: Line 14   (if x>z)
 *     Block 8: Line 15   (m=x, goto 17)
 *     Block 9: Line 17   (return m)
 *
 * Step 2: 构建CFG (Control Flow Graph)
 * ────────────────────────────────────
 *                    [Block 0: 5,6]
 *                    /            \
 *           [Block 1: 7]      [Block 5: 12]
 *           /        \         /        \
 *     [Block 2: 8] [Block 3: 9]  [Block 6: 13] [Block 7: 14]
 *          |       /      \          |        /      \
 *          |  [Block 4:10] |         |  [Block 8:15]  |
 *          |       |       |         |       |        |
 *          +-------+-------+---------+-------+--------+
 *                          |
 *                    [Block 9: 17]
 *
 * Step 3: PRS算法 - 识别可移除节点
 * ────────────────────────────────
 * PRS (Path Recovery Set) 算法判断哪些节点可以被移除：
 * - 如果节点N的所有入边和出边的组合都是唯一的，N可以被移除
 * - 移除后，从边的覆盖信息可以推断出N的覆盖
 *
 * 可移除节点: Block 2(Line 8), Block 4(Line 10), Block 6(Line 13), Block 8(Line 15)
 * 不可移除节点: Block 0,1,3,5,7,9 (它们是分支点或合流点)
 *
 * Step 4: 构建PRS边和removed_nodes
 * ─────────────────────────────────
 * PRS边 = 不可移除节点之间的边，记录中间跳过的removed_nodes
 *
 *   Edge#1: ENTRY -> :5      removed: [:6]        (进入方法时Line 6必执行)
 *   Edge#2: :5 -> :7         removed: []          (y>z 为真的分支)
 *   Edge#3: :5 -> :12        removed: []          (y>z 为假的分支)
 *   Edge#4: :7 -> :17        removed: [:8]        (x<y 为真, 跳过的Line 8)
 *   Edge#5: :7 -> :9         removed: []          (x<y 为假)
 *   Edge#6: :9 -> :10        removed: []          (x<z 为真)
 *   Edge#7: :9 -> :17        removed: []          (x<z 为假)
 *   Edge#8: :12 -> :17       removed: [:13]       (x>y 为真, 跳过的Line 13)
 *   Edge#9: :12 -> :14       removed: []          (x>y 为假)
 *   Edge#10: :14 -> :15      removed: []          (x>z 为真)
 *   Edge#11: :14 -> :17      removed: []          (x>z 为假)
 *
 * Step 5: 插装探针
 * ───────────────
 * 在每条PRS边的目标节点处插入探针:
 *   probes[edgeId] = true;
 *
 * ============== 覆盖矩阵示例 ==============
 *
 * 测试用例覆盖的边:
 *   test1: mid(3,2,1) -> edges: 1,2,4,7,8,11  结果: FAIL
 *   test6: mid(1,2,2) -> edges: 1,3,8,9,11    结果: PASS
 *
 * ============== Ranking Recovery ==============
 *
 * 从边覆盖恢复节点覆盖:
 * - 如果边 A->B 被覆盖, 则:
 *   1. B节点被覆盖
 *   2. removed_nodes中的所有节点也被覆盖
 *
 * 例如: Edge#4 (:7->:17, removed:[:8]) 被覆盖
 *       => :17 被覆盖, :8 也被覆盖
 */
public class TrueEdgeInstrumentor {

    private static final String PROBE_CLASS = "com/gzoltar/asm/runtime/GlobalProbes";
    private static final String PROBE_FIELD = "probes";
    private static final String PROBE_DESC = "[Z";

    private final List<EdgeRecord> probedEdges = new ArrayList<>();
    private final Set<String> allNodes = new TreeSet<>();
    private int nextProbeId = 0;

    /**
     * 边记录 - 保存PRS边的信息
     */
    public static class EdgeRecord {
        public final int probeId;           // 探针ID
        public final String methodKey;      // 方法标识
        public final String fromNode;       // 源节点 (如 "mid:5")
        public final String toNode;         // 目标节点 (如 "mid:7")
        public final List<String> removedNodes;  // 被跳过的节点列表

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

    /**
     * 基本块表示
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
     * DFS遍历状态 - 用于追踪removed nodes
     */
    private static class TraceState {
        final int blockId;
        final List<String> removedNodes;

        TraceState(int blockId, List<String> removedNodes) {
            this.blockId = blockId;
            this.removedNodes = removedNodes;
        }
    }

    /**
     * 插装一个类
     */
    public byte[] instrument(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        cr.accept(classNode, ClassReader.SKIP_FRAMES);

        String className = classNode.name.replace('/', '.');

        for (MethodNode method : classNode.methods) {
            // 跳过抽象方法、native方法、静态初始化块
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            if (method.name.equals("<clinit>")) {
                continue;
            }

            instrumentMethod(className, method);
        }

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
        classNode.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 插装一个方法 - 核心算法流程
     */
    private void instrumentMethod(String className, MethodNode method) {
        String methodKey = className.replace('.', '$') + "#" + method.name + "(" + getParamTypes(method.desc) + ")";

        InsnList insns = method.instructions;
        if (insns.size() == 0) return;

        // Step 0: 收集所有行号作为节点
        Set<Integer> methodLineNumbers = new HashSet<>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof LineNumberNode) {
                int line = ((LineNumberNode) insn).line;
                methodLineNumbers.add(line);
                allNodes.add(methodKey + ":" + line);
            }
        }

        // Step 1: 构建基本块
        List<BasicBlock> blocks = buildBasicBlocks(insns);
        if (blocks.isEmpty()) return;

        // Step 2: 构建CFG边
        buildCFGEdges(blocks, insns);

        // Step 3: PRS算法 - 识别可移除节点
        Set<Integer> removableBlocks = findRemovableBlocks(blocks);

        // Step 4: 构建PRS边，记录removed nodes
        buildPRSEdges(blocks, removableBlocks, methodKey, methodLineNumbers);

        // Step 5: 插入探针
        insertProbes(insns, blocks);
    }

    /**
     * Step 1: 构建基本块 build the basic blocks
     *
     * 基本块的leader: 方法入口、跳转目标、跳转后的下一条指令
     * the leader of a basic block is the method entry, jump targets, and the instruction after a jump
     */
    private List<BasicBlock> buildBasicBlocks(InsnList insns) {
        Set<Integer> leaders = new TreeSet<>();
        leaders.add(0);  // 方法入口

        // 建立Label到指令索引的映射
        Map<LabelNode, Integer> labelToIdx = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) {
            if (insns.get(i) instanceof LabelNode) {
                labelToIdx.put((LabelNode) insns.get(i), i);
            }
        }

        // 找出所有leader
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
                    leaders.add(targetIdx);  // 跳转目标
                }
                // 条件跳转的fall-through
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

        // 创建基本块
        List<Integer> sortedLeaders = new ArrayList<>(leaders);
        List<BasicBlock> blocks = new ArrayList<>();

        for (int i = 0; i < sortedLeaders.size(); i++) {
            int startIdx = sortedLeaders.get(i);
            int endIdx = (i + 1 < sortedLeaders.size()) ? sortedLeaders.get(i + 1) - 1 : insns.size() - 1;

            BasicBlock block = new BasicBlock(i, startIdx);
            block.endIdx = endIdx;

            // 收集块内的行号
            for (int j = startIdx; j <= endIdx && j < insns.size(); j++) {
                AbstractInsnNode insn = insns.get(j);
                if (insn instanceof LineNumberNode) {
                    int line = ((LineNumberNode) insn).line;
                    block.allLineNumbers.add(line);
                    if (block.lineNumber < 0) {
                        block.lineNumber = line;  // 第一个行号作为块的代表
                    }
                }
            }

            blocks.add(block);
        }

        return blocks;
    }

    /**
     * Step 2: 构建CFG边
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
            // 找到块的最后一条可执行指令
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
                // 空块，fall-through到下一块
                if (block.id + 1 < blocks.size()) {
                    addEdge(block, blocks.get(block.id + 1));
                }
                continue;
            }

            // 根据最后一条指令确定后继
            if (lastInsn instanceof JumpInsnNode) {
                JumpInsnNode jump = (JumpInsnNode) lastInsn;
                Integer targetIdx = labelToIdx.get(jump.label);
                if (targetIdx != null) {
                    Integer targetBlock = idxToBlock.get(targetIdx);
                    if (targetBlock != null) {
                        addEdge(block, blocks.get(targetBlock));
                    }
                }
                // 条件跳转还有fall-through
                if (lastInsn.getOpcode() != Opcodes.GOTO && block.id + 1 < blocks.size()) {
                    addEdge(block, blocks.get(block.id + 1));
                }
            }
            else if (lastInsn instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode sw = (TableSwitchInsnNode) lastInsn;
                addSwitchEdges(block, blocks, sw.dflt, sw.labels, labelToIdx, idxToBlock);
            }
            else if (lastInsn instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode sw = (LookupSwitchInsnNode) lastInsn;
                addSwitchEdges(block, blocks, sw.dflt, sw.labels, labelToIdx, idxToBlock);
            }
            else if (!isReturnOrThrow(lastInsn.getOpcode())) {
                // 普通指令，fall-through
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
     * Step 3: PRS算法 - 识别可移除的基本块
     *
     * 使用共享的 PRSAlgorithm 类实现。
     * 详见 PRSAlgorithm.java 中的算法说明。
     */
    private Set<Integer> findRemovableBlocks(List<BasicBlock> blocks) {
        // 转换为 PRSAlgorithm 的 Block 格式
        List<PRSAlgorithm.Block> prsBlocks = new ArrayList<>();
        for (BasicBlock block : blocks) {
            prsBlocks.add(new PRSAlgorithm.Block(
                block.id,
                block.predecessors,
                block.successors
            ));
        }

        // 调用共享的 PRS 算法
        PRSAlgorithm.PRSResult result = PRSAlgorithm.findRemovableBlocks(prsBlocks);
        return result.removableBlocks;
    }

    /**
     * Step 4: 构建PRS边，记录removed nodes
     */
    private void buildPRSEdges(List<BasicBlock> blocks, Set<Integer> removable,
                               String methodKey, Set<Integer> methodLineNumbers) {

        Set<Integer> nonRemovable = new HashSet<>();
        for (BasicBlock block : blocks) {
            if (!removable.contains(block.id)) {
                nonRemovable.add(block.id);
            }
        }

        // ENTRY边
        if (!methodLineNumbers.isEmpty()) {
            int firstLine = Collections.min(methodLineNumbers);
            String entryNode = methodKey + ":ENTRY";
            String firstNode = methodKey + ":" + firstLine;

            // 找出ENTRY必经的removed nodes
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

                    for (int line : block.allLineNumbers) {
                        if (line != firstLine) {
                            alwaysExecutedLines.add(line);
                        }
                    }

                    // 只有单一后继才是必经的
                    if (block.successors.size() == 1) {
                        for (int succId : block.successors) {
                            if (!visited.contains(succId)) {
                                visited.add(succId);
                                queue.add(succId);
                            }
                        }
                    }
                }

                for (int line : alwaysExecutedLines) {
                    entryRemovedNodes.add(methodKey + ":" + line);
                }
            }

            probedEdges.add(new EdgeRecord(nextProbeId++, methodKey, entryNode, firstNode, entryRemovedNodes));
        }

        // 非移除块之间的PRS边
        for (int srcId : nonRemovable) {
            BasicBlock srcBlock = blocks.get(srcId);

            String srcNode = srcBlock.lineNumber > 0 ?
                methodKey + ":" + srcBlock.lineNumber :
                methodKey + ":BLOCK" + srcId;

            // 从每个直接后继开始，穿过removable块找到目标
            for (int directSuccId : srcBlock.successors) {
                Stack<TraceState> stack = new Stack<>();
                stack.push(new TraceState(directSuccId, new ArrayList<>()));

                while (!stack.isEmpty()) {
                    TraceState state = stack.pop();
                    int currentId = state.blockId;
                    List<String> removedNodes = new ArrayList<>(state.removedNodes);

                    // 穿过removable块
                    while (removable.contains(currentId)) {
                        BasicBlock current = blocks.get(currentId);
                        // 记录被跳过的行号
                        for (int line : current.allLineNumbers) {
                            removedNodes.add(methodKey + ":" + line);
                        }

                        if (current.successors.isEmpty()) {
                            break;
                        } else if (current.successors.size() == 1) {
                            currentId = current.successors.iterator().next();
                        } else {
                            // 分支点，每个分支单独追踪
                            for (int nextId : current.successors) {
                                stack.push(new TraceState(nextId, new ArrayList<>(removedNodes)));
                            }
                            break;
                        }
                    }

                    // 到达非removable块
                    if (!removable.contains(currentId)) {
                        BasicBlock dstBlock = blocks.get(currentId);
                        if (dstBlock.lineNumber > 0) {
                            String dstNode = methodKey + ":" + dstBlock.lineNumber;

                            // 目标块内的其他行号也是removed
                            for (int line : dstBlock.allLineNumbers) {
                                if (line != dstBlock.lineNumber) {
                                    removedNodes.add(methodKey + ":" + line);
                                }
                            }

                            // 只有当有removed nodes或源有多个后继时才需要边
                            if (!removedNodes.isEmpty() || srcBlock.successors.size() > 1) {
                                probedEdges.add(new EdgeRecord(nextProbeId++, methodKey, srcNode, dstNode, removedNodes));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Step 5: 插入探针
     */
    private void insertProbes(InsnList insns, List<BasicBlock> blocks) {
        // 建立行号到插入点的映射
        Map<Integer, AbstractInsnNode> lineToInsertPoint = new HashMap<>();
        for (AbstractInsnNode insn : insns) {
            if (insn instanceof LineNumberNode) {
                int line = ((LineNumberNode) insn).line;
                AbstractInsnNode next = insn.getNext();
                while (next != null &&
                       (next.getType() == AbstractInsnNode.LABEL ||
                        next.getType() == AbstractInsnNode.LINE ||
                        next.getType() == AbstractInsnNode.FRAME)) {
                    next = next.getNext();
                }
                if (next != null && !lineToInsertPoint.containsKey(line)) {
                    lineToInsertPoint.put(line, next);
                }
            }
        }

        Set<Integer> insertedProbes = new HashSet<>();

        // 插入探针
        for (EdgeRecord edge : probedEdges) {
            if (insertedProbes.contains(edge.probeId)) continue;

            AbstractInsnNode insertPoint = null;

            if (edge.fromNode.endsWith(":ENTRY")) {
                // ENTRY探针插入方法开头
                insertPoint = findFirstExecutable(insns);
            } else {
                // 其他探针插入目标行
                String lineSuffix = edge.toNode.substring(edge.toNode.lastIndexOf(':') + 1);
                try {
                    int line = Integer.parseInt(lineSuffix);
                    insertPoint = lineToInsertPoint.get(line);
                } catch (NumberFormatException e) {
                    // 忽略非数字行号
                }
            }

            if (insertPoint != null) {
                insns.insertBefore(insertPoint, createProbeCode(edge.probeId));
                insertedProbes.add(edge.probeId);
            }
        }
    }

    private AbstractInsnNode findFirstExecutable(InsnList insns) {
        for (AbstractInsnNode insn : insns) {
            if (insn.getType() != AbstractInsnNode.LABEL &&
                insn.getType() != AbstractInsnNode.LINE &&
                insn.getType() != AbstractInsnNode.FRAME) {
                return insn;
            }
        }
        return null;
    }

    /**
     * 生成探针代码: GlobalProbes.probes[probeId] = true;
     */
    private InsnList createProbeCode(int probeId) {
        InsnList code = new InsnList();
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, PROBE_CLASS, PROBE_FIELD, PROBE_DESC));
        if (probeId <= 5) {
            code.add(new InsnNode(Opcodes.ICONST_0 + probeId));
        } else if (probeId <= 127) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, probeId));
        } else if (probeId <= 32767) {
            code.add(new IntInsnNode(Opcodes.SIPUSH, probeId));
        } else {
            code.add(new LdcInsnNode(probeId));
        }
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.BASTORE));
        return code;
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
                    String clsName = desc.substring(i + 1, end).replace('/', '.');
                    sb.append(clsName.substring(clsName.lastIndexOf('.') + 1));
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
                default: i++;
            }
        }
        return sb.toString();
    }

    // ========== Public API ==========

    public int getProbeCount() { return nextProbeId; }
    public List<EdgeRecord> getProbedEdges() { return new ArrayList<>(probedEdges); }
    public Set<String> getAllNodes() { return new TreeSet<>(allNodes); }
}
