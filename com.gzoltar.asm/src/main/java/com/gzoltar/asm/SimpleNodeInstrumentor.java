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
 * Simple node-based instrumentor that inserts a probe at each unique line number.
 * This provides line-level coverage similar to Basic Block granularity.
 */
public class SimpleNodeInstrumentor {

    private static final String PROBE_CLASS = "com/gzoltar/asm/runtime/GlobalProbes";
    private static final String PROBE_FIELD = "probes";
    private static final String PROBE_DESC = "[Z";

    /** Map from node name to probe ID */
    private final Map<String, Integer> nodeToProbeId = new LinkedHashMap<>();

    /** List of all nodes (spectra) */
    private final List<String> spectra = new ArrayList<>();

    /** Current probe ID counter */
    private int nextProbeId = 0;

    /**
     * Analyze a class to find all instrumentation points.
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
     * Analyze a method to find all line numbers.
     */
    private void analyzeMethod(String className, MethodNode method) {
        Set<Integer> seenLines = new HashSet<>();

        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode) {
                int line = ((LineNumberNode) insn).line;
                if (!seenLines.contains(line)) {
                    seenLines.add(line);
                    String nodeName = className.replace('.', '$') + "#" + method.name + "(" +
                        getParamTypes(method.desc) + "):" + line;
                    if (!nodeToProbeId.containsKey(nodeName)) {
                        nodeToProbeId.put(nodeName, nextProbeId++);
                        spectra.add(nodeName);
                    }
                }
            }
        }
    }

    /**
     * Instrument a class by inserting probes at each line.
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
            System.err.println("[SimpleNodeInstrumentor] Error: " + e.getMessage());
            return classBytes;
        }
    }

    /**
     * Instrument a method by inserting probes after each LineNumberNode.
     */
    private void instrumentMethod(String className, MethodNode method) {
        InsnList instructions = method.instructions;
        Set<Integer> instrumentedLines = new HashSet<>();

        // Collect insertion points
        List<AbstractInsnNode> insertionPoints = new ArrayList<>();
        for (AbstractInsnNode insn : instructions) {
            if (insn instanceof LineNumberNode) {
                int line = ((LineNumberNode) insn).line;
                if (!instrumentedLines.contains(line)) {
                    instrumentedLines.add(line);
                    insertionPoints.add(insn);
                }
            }
        }

        // Insert probes after each LineNumberNode
        for (AbstractInsnNode lineNode : insertionPoints) {
            int line = ((LineNumberNode) lineNode).line;
            String nodeName = className.replace('.', '$') + "#" + method.name + "(" +
                getParamTypes(method.desc) + "):" + line;

            Integer probeId = nodeToProbeId.get(nodeName);
            if (probeId != null) {
                InsnList probeCode = createProbeInstructions(probeId);
                instructions.insert(lineNode, probeCode);
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

    /**
     * Get parameter types from method descriptor.
     */
    private String getParamTypes(String desc) {
        StringBuilder sb = new StringBuilder();
        int i = 1; // Skip '('
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
                    // Handle the element type (simplified)
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

    public List<String> getSpectra() {
        return new ArrayList<>(spectra);
    }

    public Map<String, Integer> getNodeToProbeId() {
        return new LinkedHashMap<>(nodeToProbeId);
    }
}
