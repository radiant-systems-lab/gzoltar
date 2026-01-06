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
package com.gzoltar.report.fl.formatter.txt;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.gzoltar.core.model.Node;
import com.gzoltar.core.model.Transaction;
import com.gzoltar.core.model.TransactionOutcome;
import com.gzoltar.core.runtime.Probe;
import com.gzoltar.core.runtime.ProbeGroup;
import com.gzoltar.core.spectrum.ISpectrum;
import com.gzoltar.fl.IFormula;
import com.gzoltar.report.fl.EdgeRankingExpander;
import com.gzoltar.report.fl.formatter.IFaultLocalizationReportFormatter;

public class FaultLocalizationTxtReport implements IFaultLocalizationReportFormatter {

  private final static String MATRIX_FILE_NAME = "matrix.txt";

  private final static String SPECTRA_FILE_NAME = "spectra.csv";

  private final static String RANKING_EXTENSION_NAME = ".ranking.csv";

  private final static String NODE_RANKING_EXTENSION_NAME = ".node-ranking.csv";

  private final static String TESTS_FILES_NAME = "tests.csv";

  /** Tracks whether edge granularity is being used */
  private boolean isEdgeGranularity = false;

  /**
   * {@inheritDoc}
   */
  @Override
  public void generateFaultLocalizationReport(final File outputDirectory, final ISpectrum spectrum,
      final List<IFormula> formulas) throws IOException {
    if (!outputDirectory.exists()) {
      outputDirectory.mkdirs();
    }

    List<ProbeGroup> probeGroups = new ArrayList<ProbeGroup>(spectrum.getProbeGroups());

    List<Transaction> transactions = spectrum.getTransactions();

    /**
     * Print 'matrix'
     */

    PrintWriter matrixWriter =
        new PrintWriter(outputDirectory + File.separator + MATRIX_FILE_NAME, "UTF-8");

    for (Transaction transaction : transactions) {
      StringBuilder transactionStr = new StringBuilder();

      for (ProbeGroup probeGroup : probeGroups) {
        for (Probe probe : probeGroup.getProbes()) {
          if (transaction.isProbeActived(probeGroup, probe.getArrayIndex())) {
            transactionStr.append("1 ");
          } else {
            transactionStr.append("0 ");
          }
        }
      }

      if (transaction.hasFailed()) {
        transactionStr.append(TransactionOutcome.FAIL.getSymbol());
      } else {
        transactionStr.append(TransactionOutcome.PASS.getSymbol());
      }

      matrixWriter.println(transactionStr.toString());
    }

    matrixWriter.close();

    /**
     * Print 'spectra'
     */

    PrintWriter spectraWriter =
        new PrintWriter(outputDirectory + File.separator + SPECTRA_FILE_NAME, "UTF-8");

    // header
    spectraWriter.println("name");

    // content
    for (ProbeGroup probeGroup : probeGroups) {
      for (Probe probe : probeGroup.getProbes()) {
        spectraWriter.println(probe.getNode().getNameWithLineNumber());
      }
    }

    spectraWriter.close();

    /**
     * Print a ranking file per formula
     */

    List<Node> nodes = new ArrayList<Node>(spectrum.getNodes());

    // Detect if edge granularity is being used by checking for #EDGE: in node names
    boolean hasEdgeNodes = false;
    for (Node node : nodes) {
      if (node.getNameWithLineNumber().contains("#EDGE:")) {
        hasEdgeNodes = true;
        break;
      }
    }

    for (final IFormula formula : formulas) {

      String rankingFilePath = outputDirectory + File.separator
          + formula.getName().toLowerCase() + RANKING_EXTENSION_NAME;

      PrintWriter formulaWriter = new PrintWriter(rankingFilePath, "UTF-8");

      // header
      formulaWriter.println("name;suspiciousness_value");

      // sort (DESC) nodes by their suspiciousness value
      Collections.sort(nodes, new Comparator<Node>() {
        @Override
        public int compare(Node node0, Node node1) {
          return Double.compare(node1.getSuspiciousnessValue(formula.getName()),
              node0.getSuspiciousnessValue(formula.getName()));
        }
      });

      // Write nodes directly with edge labels (no expansion)
      for (Node node : nodes) {
        String nodeName = node.getNameWithLineNumber();
        double suspiciousness = node.getSuspiciousnessValue(formula.getName());

        // Write the node with its original name (including edge labels like "14->15")
        formulaWriter.println(nodeName + ";" + suspiciousness);
      }

      formulaWriter.close();

      // If edge granularity, also generate node-level ranking from coverage matrix
      if (hasEdgeNodes) {
        generateNodeRankingFromCoverage(outputDirectory, formula.getName(),
            probeGroups, transactions);
      }
    }

    /**
     * Print 'tests'
     */

    PrintWriter testsWriter =
        new PrintWriter(outputDirectory + File.separator + TESTS_FILES_NAME, "UTF-8");

    // header
    testsWriter.println("name,outcome,runtime,stacktrace");

    // content
    for (Transaction transaction : transactions) {
      testsWriter.println(transaction.getName() + ","
          + (transaction.hasFailed() ? TransactionOutcome.FAIL.name()
              : TransactionOutcome.PASS.name())
          + "," + transaction.getRuntime() + "," + transaction.getStackTrace());
    }

    testsWriter.close();
  }

  /**
   * Generate node-level ranking from edge coverage matrix.
   * This method recovers node coverage from edge coverage and recalculates Ochiai.
   *
   * Uses EdgeAnnotationRegistry to get edge metadata (coveredLines, removedNodes)
   * for accurate node coverage recovery.
   *
   * For edge A->B:
   *   - All lines in coveredLines are covered
   *   - All removedNodes are covered
   *
   * @param outputDirectory The output directory
   * @param formulaName Name of the formula (e.g., "ochiai")
   * @param probeGroups The probe groups containing edge probes
   * @param transactions The test transactions with coverage data
   */
  private void generateNodeRankingFromCoverage(File outputDirectory, String formulaName,
      List<ProbeGroup> probeGroups, List<Transaction> transactions) {
    try {
      // Try to use EdgeAnnotationRegistry for accurate recovery
      com.gzoltar.core.model.EdgeAnnotationRegistry registry =
          com.gzoltar.core.model.EdgeAnnotationRegistry.getInstance();

      if (registry.size() > 0) {
        // Use NodeCoverageRecovery for accurate recovery
        generateNodeRankingWithRegistry(outputDirectory, formulaName, probeGroups, transactions, registry);
      } else {
        // Fallback to simple edge pattern matching
        generateNodeRankingSimple(outputDirectory, formulaName, probeGroups, transactions);
      }

    } catch (IOException e) {
      System.err.println("Warning: Failed to generate node ranking from edges: " + e.getMessage());
    }
  }

  /**
   * Generate node ranking using EdgeAnnotationRegistry for accurate recovery.
   */
  private void generateNodeRankingWithRegistry(File outputDirectory, String formulaName,
      List<ProbeGroup> probeGroups, List<Transaction> transactions,
      com.gzoltar.core.model.EdgeAnnotationRegistry registry) throws IOException {

    // Build probe index to edge annotation mapping
    Map<Integer, com.gzoltar.core.model.EdgeAnnotation> probeToAnnotation = new HashMap<>();
    for (com.gzoltar.core.model.EdgeAnnotation annotation : registry.getAllAnnotations()) {
      probeToAnnotation.put(annotation.getProbeId(), annotation);
    }

    // Collect all node names from annotations
    Set<String> allNodeNames = new HashSet<>();
    for (com.gzoltar.core.model.EdgeAnnotation annotation : registry.getAllAnnotations()) {
      String methodKey = annotation.getMethodKey();
      for (Integer line : annotation.getCoveredLines()) {
        allNodeNames.add(methodKey + ":" + line);
      }
      allNodeNames.add(annotation.getToNode());
      allNodeNames.addAll(annotation.getRemovedNodes());
    }

    // Build node coverage matrix
    Map<String, int[]> nodeCoverage = new HashMap<>();
    for (String node : allNodeNames) {
      nodeCoverage.put(node, new int[4]);  // ef, ep, nf, np
    }

    int totalFailed = 0;
    int totalPassed = 0;
    for (Transaction transaction : transactions) {
      if (transaction.hasFailed()) {
        totalFailed++;
      } else {
        totalPassed++;
      }
    }

    // Process each transaction
    int probeIndex = 0;
    for (Transaction transaction : transactions) {
      boolean testFailed = transaction.hasFailed();
      Set<String> coveredNodesInTest = new HashSet<>();

      // Collect covered nodes from activated probes
      probeIndex = 0;
      for (ProbeGroup probeGroup : probeGroups) {
        for (com.gzoltar.core.runtime.Probe probe : probeGroup.getProbes()) {
          if (transaction.isProbeActived(probeGroup, probe.getArrayIndex())) {
            com.gzoltar.core.model.EdgeAnnotation annotation = probeToAnnotation.get(probeIndex);
            if (annotation != null) {
              String methodKey = annotation.getMethodKey();
              // Add all covered lines
              for (Integer line : annotation.getCoveredLines()) {
                coveredNodesInTest.add(methodKey + ":" + line);
              }
              // Add target node
              coveredNodesInTest.add(annotation.getToNode());
              // Add removed nodes
              coveredNodesInTest.addAll(annotation.getRemovedNodes());
            }
          }
          probeIndex++;
        }
      }

      // Update coverage counts
      for (String node : allNodeNames) {
        int[] counts = nodeCoverage.get(node);
        boolean covered = coveredNodesInTest.contains(node);

        if (covered && testFailed) {
          counts[0]++;  // ef
        } else if (covered && !testFailed) {
          counts[1]++;  // ep
        } else if (!covered && testFailed) {
          counts[2]++;  // nf
        } else {
          counts[3]++;  // np
        }
      }
    }

    // Calculate Ochiai for each node
    Map<String, Double> nodeRanking = new LinkedHashMap<>();
    for (String node : allNodeNames) {
      int[] counts = nodeCoverage.get(node);
      int ef = counts[0];
      int ep = counts[1];

      double ochiai = 0.0;
      if (ef > 0 && totalFailed > 0) {
        double denom = Math.sqrt((double) totalFailed * (ef + ep));
        if (denom > 0) {
          ochiai = ef / denom;
        }
      }
      nodeRanking.put(node, ochiai);
    }

    // Sort and write
    List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(nodeRanking.entrySet());
    Collections.sort(sortedEntries, new Comparator<Map.Entry<String, Double>>() {
      @Override
      public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
        int cmp = Double.compare(b.getValue(), a.getValue());
        if (cmp != 0) return cmp;
        return a.getKey().compareTo(b.getKey());
      }
    });

    String nodeRankingPath = outputDirectory + File.separator
        + formulaName.toLowerCase() + NODE_RANKING_EXTENSION_NAME;

    PrintWriter nodeWriter = new PrintWriter(nodeRankingPath, "UTF-8");
    nodeWriter.println("name;suspiciousness_value");

    for (Map.Entry<String, Double> entry : sortedEntries) {
      nodeWriter.println(entry.getKey() + ";" + entry.getValue());
    }

    nodeWriter.close();
  }

  /**
   * Fallback: generate node ranking using simple edge pattern matching.
   */
  private void generateNodeRankingSimple(File outputDirectory, String formulaName,
      List<ProbeGroup> probeGroups, List<Transaction> transactions) throws IOException {
    // Pattern to extract edge info: method:line#EDGE:from->to
    Pattern edgePattern = Pattern.compile("^(.+):([0-9]+)#EDGE:([A-Z0-9]+)->([0-9]+)$");

    Map<Integer, List<String>> probeToNodes = new HashMap<>();
    Set<String> allNodeNames = new HashSet<>();

    int probeIndex = 0;
    for (ProbeGroup probeGroup : probeGroups) {
      for (Probe probe : probeGroup.getProbes()) {
        String edgeName = probe.getNode().getNameWithLineNumber();
        Matcher matcher = edgePattern.matcher(edgeName);

        List<String> coveredNodes = new ArrayList<>();
        if (matcher.find()) {
          String methodPrefix = matcher.group(1);
          String targetLine = matcher.group(2);

          String targetNode = methodPrefix + ":" + targetLine;
          coveredNodes.add(targetNode);
          allNodeNames.add(targetNode);
        }
        probeToNodes.put(probeIndex, coveredNodes);
        probeIndex++;
      }
    }

    Map<String, int[]> nodeCoverage = new HashMap<>();
    for (String node : allNodeNames) {
      nodeCoverage.put(node, new int[4]);
    }

    int totalFailed = 0;
    for (Transaction transaction : transactions) {
      if (transaction.hasFailed()) {
        totalFailed++;
      }
    }

    for (Transaction transaction : transactions) {
      boolean testFailed = transaction.hasFailed();
      Set<String> coveredNodesInTest = new HashSet<>();

      probeIndex = 0;
      for (ProbeGroup probeGroup : probeGroups) {
        for (Probe probe : probeGroup.getProbes()) {
          if (transaction.isProbeActived(probeGroup, probe.getArrayIndex())) {
            List<String> nodes = probeToNodes.get(probeIndex);
            if (nodes != null) {
              coveredNodesInTest.addAll(nodes);
            }
          }
          probeIndex++;
        }
      }

      for (String node : allNodeNames) {
        int[] counts = nodeCoverage.get(node);
        boolean covered = coveredNodesInTest.contains(node);

        if (covered && testFailed) {
          counts[0]++;
        } else if (covered && !testFailed) {
          counts[1]++;
        } else if (!covered && testFailed) {
          counts[2]++;
        } else {
          counts[3]++;
        }
      }
    }

    Map<String, Double> nodeRanking = new LinkedHashMap<>();
    for (String node : allNodeNames) {
      int[] counts = nodeCoverage.get(node);
      int ef = counts[0];
      int ep = counts[1];

      double ochiai = 0.0;
      if (ef > 0 && totalFailed > 0) {
        double denom = Math.sqrt((double) totalFailed * (ef + ep));
        if (denom > 0) {
          ochiai = ef / denom;
        }
      }
      nodeRanking.put(node, ochiai);
    }

    List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(nodeRanking.entrySet());
    Collections.sort(sortedEntries, new Comparator<Map.Entry<String, Double>>() {
      @Override
      public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
        int cmp = Double.compare(b.getValue(), a.getValue());
        if (cmp != 0) return cmp;
        return a.getKey().compareTo(b.getKey());
      }
    });

    String nodeRankingPath = outputDirectory + File.separator
        + formulaName.toLowerCase() + NODE_RANKING_EXTENSION_NAME;

    PrintWriter nodeWriter = new PrintWriter(nodeRankingPath, "UTF-8");
    nodeWriter.println("name;suspiciousness_value");

    for (Map.Entry<String, Double> entry : sortedEntries) {
      nodeWriter.println(entry.getKey() + ";" + entry.getValue());
    }

    nodeWriter.close();
  }
}
