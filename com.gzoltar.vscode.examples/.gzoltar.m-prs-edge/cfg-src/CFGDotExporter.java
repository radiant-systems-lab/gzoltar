import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

import com.gzoltar.core.instr.cfg.CFGBuilder;
import com.gzoltar.core.instr.cfg.ControlFlowGraph;
import com.gzoltar.core.instr.cfg.BasicBlockNode;
import com.gzoltar.core.instr.cfg.PathRecoveryOrder;

public class CFGDotExporter {
  public static void main(String[] args) throws Exception {
    Map<String, String> argMap = parseArgs(args);
    String classesDir = required(argMap, "--classesDir");
    String fqcn = required(argMap, "--class");
    String outDir = required(argMap, "--outDir");

    new File(outDir).mkdirs();

    ClassPool pool = ClassPool.getDefault();
    pool.insertClassPath(classesDir);

    CtClass ct = pool.get(fqcn);

    for (CtMethod method : ct.getDeclaredMethods()) {
      try {
        ControlFlowGraph cfg = CFGBuilder.buildFromControlFlow(ct, method.getMethodInfo());
        String base = sanitize(fqcn) + "_" + method.getName();

        writeDot(new File(outDir, base + "_cfg.dot"), cfg, null);

        Set<BasicBlockNode> removable = cfg.findMinimalNodes(PathRecoveryOrder.BOTH);
        Set<Integer> removableIds = new HashSet<Integer>();
        for (BasicBlockNode node : removable) {
          removableIds.add(node.id);
        }
        writeDot(new File(outDir, base + "_cfg_pruned.dot"), cfg, removableIds);
      } catch (Throwable t) {
        System.err.println("Failed to export CFG for method: " + method.getLongName());
        t.printStackTrace();
      }
    }
  }

  private static void writeDot(File file, ControlFlowGraph cfg, Set<Integer> skipIds) throws Exception {
    try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
      pw.println("digraph \"" + cfg.methodName + "\" {");
      pw.println("  rankdir=LR;");
      pw.println("  node [shape=box,fontname=Courier];");

      for (BasicBlockNode node : cfg.nodes.values()) {
        if (skipIds != null && skipIds.contains(node.id)) {
          continue;
        }
        pw.println("  n" + node.id + " [label=\"" + node.id + "@" + node.offset + "\"]; ");
      }

      for (Map.Entry<BasicBlockNode, Set<BasicBlockNode>> entry : cfg.outEdges.entrySet()) {
        BasicBlockNode from = entry.getKey();
        if (skipIds != null && skipIds.contains(from.id)) {
          continue;
        }
        for (BasicBlockNode to : entry.getValue()) {
          if (skipIds != null && skipIds.contains(to.id)) {
            continue;
          }
          pw.println("  n" + from.id + " -> n" + to.id + ";");
        }
      }

      pw.println("}");
    }
  }

  private static Map<String, String> parseArgs(String[] args) {
    java.util.HashMap<String, String> map = new java.util.HashMap<String, String>();
    for (int i = 0; i < args.length - 1; i += 2) {
      map.put(args[i], args[i + 1]);
    }
    return map;
  }

  private static String required(Map<String, String> map, String key) {
    if (!map.containsKey(key)) {
      throw new IllegalArgumentException("Missing argument: " + key);
    }
    return map.get(key);
  }

  private static String sanitize(String input) {
    return input.replace('.', '_').replace('$', '_');
  }
}
