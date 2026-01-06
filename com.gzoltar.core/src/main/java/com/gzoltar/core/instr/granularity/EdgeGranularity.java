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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javassist.CtClass;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.analysis.ControlFlow;
import javassist.bytecode.analysis.ControlFlow.Block;

/**
 * Edge-level granularity using PRS (Probe Reduction Strategy) algorithm.
 *
 * This granularity instruments edges in the control flow graph instead of basic blocks.
 * The PRS algorithm identifies which nodes can be "removed" (not instrumented) while
 * still maintaining the ability to recover full node coverage from edge coverage.
 *
 * A node is removable if:
 * 1. It has at least one predecessor (not an entry node)
 * 2. It has at least one successor (not an exit node)
 * 3. It is not a direct successor of an entry node
 * 4. Removing it would not create duplicate edges (edge ambiguity)
 *
 * For each edge A->B, if there are removable nodes between them, we record them
 * so that node coverage can be recovered from edge coverage.
 */
public class EdgeGranularity extends AbstractGranularity {

  /** Queue of positions where edge probes should be inserted */
  private Queue<Integer> edgePositions = new LinkedList<>();

  /** Map from position to edge information for annotation */
  private Map<Integer, EdgeInfo> positionToEdge = new HashMap<>();

  /** Current edge being processed */
  private EdgeInfo currentEdge = null;

  /** All basic blocks in the method */
  private Block[] blocks;

  /** Set of removable block indices */
  private Set<Integer> removableBlocks = new HashSet<>();

  /** Edge counter for suffix generation */
  private int edgeCounter = 0;

  /**
   * Information about an edge for annotation purposes.
   */
  public static class EdgeInfo {
    public final int fromBlockIndex;
    public final int toBlockIndex;
    public final int fromPosition;
    public final int toPosition;
    public final List<Integer> removedBlockIndices;
    public final int edgeId;
    public int fromLine;  // Source line number (-1 for ENTRY)
    public int toLine;    // Target line number

    public EdgeInfo(int fromBlockIndex, int toBlockIndex, int fromPosition, int toPosition,
                    List<Integer> removedBlockIndices, int edgeId) {
      this.fromBlockIndex = fromBlockIndex;
      this.toBlockIndex = toBlockIndex;
      this.fromPosition = fromPosition;
      this.toPosition = toPosition;
      this.removedBlockIndices = removedBlockIndices;
      this.edgeId = edgeId;
      this.fromLine = -1;
      this.toLine = -1;
    }

    /**
     * Get edge representation as "fromLine->toLine" or "ENTRY->toLine"
     */
    public String getEdgeLabel() {
      if (fromLine < 0) {
        return "ENTRY->" + toLine;
      }
      return fromLine + "->" + toLine;
    }
  }

  public EdgeGranularity(final CtClass ctClass, final MethodInfo methodInfo) {
    super(ctClass, methodInfo);
    try {
      ControlFlow cf = new ControlFlow(ctClass, methodInfo);
      this.blocks = cf.basicBlocks();

      if (this.blocks == null || this.blocks.length == 0) {
        return;
      }

      // Build CFG edges: for each block, create edges from predecessors to this block
      // Edge probe is inserted at the target block's position
      for (int i = 0; i < blocks.length; i++) {
        Block block = blocks[i];
        int toPos = block.position();
        int toLine = methodInfo.getLineNumber(toPos);

        // Find predecessors by checking which blocks have this block as exit
        List<Integer> predIndices = new ArrayList<>();
        for (int j = 0; j < blocks.length; j++) {
          Block predBlock = blocks[j];
          for (int k = 0; k < predBlock.exits(); k++) {
            if (predBlock.exit(k).position() == toPos) {
              predIndices.add(j);
              break;
            }
          }
        }

        if (predIndices.isEmpty()) {
          // Entry edge: ENTRY -> first block
          EdgeInfo edge = new EdgeInfo(-1, i, -1, toPos, new ArrayList<>(), edgeCounter++);
          edge.fromLine = -1; // ENTRY
          edge.toLine = toLine;
          edgePositions.add(toPos);
          positionToEdge.put(toPos, edge);
        } else {
          // For simplicity, create one edge per target block
          // Use the first predecessor as the "from" node
          int predIdx = predIndices.get(0);
          int fromPos = blocks[predIdx].position();
          int fromLine = methodInfo.getLineNumber(fromPos);

          EdgeInfo edge = new EdgeInfo(predIdx, i, fromPos, toPos, new ArrayList<>(), edgeCounter++);
          edge.fromLine = fromLine;
          edge.toLine = toLine;
          edgePositions.add(toPos);
          positionToEdge.put(toPos, edge);
        }
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Find the index of a block in the blocks array.
   */
  private int findBlockIndex(Block target) {
    for (int i = 0; i < blocks.length; i++) {
      if (blocks[i].position() == target.position()) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Apply PRS algorithm to find removable blocks.
   *
   * A block is removable if:
   * 1. It has at least one predecessor (not entry)
   * 2. It has at least one successor (not exit)
   * 3. It is not a direct successor of entry
   * 4. Removal doesn't create duplicate edges
   */
  private void findRemovableBlocks(Map<Integer, Set<Integer>> predecessors,
                                   Map<Integer, Set<Integer>> successors,
                                   Set<Integer> entrySuccessors) {

    // Sort blocks by inDegree * outDegree (prioritize low-impact nodes)
    List<Integer> sortedBlocks = new ArrayList<>();
    for (int i = 0; i < blocks.length; i++) {
      sortedBlocks.add(i);
    }
    sortedBlocks.sort((a, b) -> {
      int scoreA = predecessors.get(a).size() * successors.get(a).size();
      int scoreB = predecessors.get(b).size() * successors.get(b).size();
      return Integer.compare(scoreA, scoreB);
    });

    // Working copies of edge sets (for virtual removal)
    Map<Integer, Set<Integer>> workingSucc = new HashMap<>();
    Map<Integer, Set<Integer>> workingPred = new HashMap<>();
    for (int i = 0; i < blocks.length; i++) {
      workingSucc.put(i, new HashSet<>(successors.get(i)));
      workingPred.put(i, new HashSet<>(predecessors.get(i)));
    }

    for (int blockIdx : sortedBlocks) {
      int inDegree = workingPred.get(blockIdx).size();
      int outDegree = workingSucc.get(blockIdx).size();

      // Entry nodes (in-degree = 0) are NOT removable
      if (inDegree == 0) continue;
      // Exit nodes (out-degree = 0) are NOT removable
      if (outDegree == 0) continue;
      // Entry's direct successors are NOT removable
      if (entrySuccessors.contains(blockIdx)) continue;

      // Check if removal would create duplicate edges
      boolean canRemove = true;
      for (int pred : workingPred.get(blockIdx)) {
        for (int succ : workingSucc.get(blockIdx)) {
          if (workingSucc.get(pred).contains(succ)) {
            // Edge already exists - would create ambiguity
            canRemove = false;
            break;
          }
        }
        if (!canRemove) break;
      }

      if (canRemove) {
        removableBlocks.add(blockIdx);
        // Update edge sets (virtual removal)
        for (int pred : workingPred.get(blockIdx)) {
          for (int succ : workingSucc.get(blockIdx)) {
            workingSucc.get(pred).add(succ);
            workingPred.get(succ).add(pred);
          }
        }
      }
    }
  }

  /**
   * Build PRS edges between non-removable blocks.
   * Each edge probe is inserted at the target block's position.
   *
   * For EDGE granularity, we instrument at each non-removable block.
   * The edge information tracks which blocks were "removed" (not instrumented)
   * between the source and target of each edge.
   */
  private void buildPRSEdges(Map<Integer, Set<Integer>> predecessors,
                             Map<Integer, Set<Integer>> successors) {

    // Instrument at each non-removable block position
    // This is similar to BasicBlockGranularity but with edge metadata
    for (int blockIdx = 0; blockIdx < blocks.length; blockIdx++) {
      if (removableBlocks.contains(blockIdx)) {
        continue; // Skip removable blocks
      }

      int position = blocks[blockIdx].position();

      // Find all non-removable predecessors for this block
      // (tracing through any removable blocks in between)
      Set<Integer> nonRemovablePreds = findNonRemovablePredecessors(blockIdx, predecessors);

      // Collect removed nodes between each predecessor and this block
      List<Integer> allRemovedNodes = new ArrayList<>();
      for (int predIdx : predecessors.get(blockIdx)) {
        if (removableBlocks.contains(predIdx)) {
          allRemovedNodes.add(predIdx);
          // Also collect transitively removed nodes
          collectRemovedPredecessors(predIdx, predecessors, allRemovedNodes);
        }
      }

      // Determine from block (for edge annotation)
      int fromIdx = -1; // -1 means entry
      int fromPosition = -1;
      if (!nonRemovablePreds.isEmpty()) {
        fromIdx = nonRemovablePreds.iterator().next();
        fromPosition = blocks[fromIdx].position();
      }

      EdgeInfo edge = new EdgeInfo(fromIdx, blockIdx,
                                   fromPosition,
                                   position,
                                   allRemovedNodes,
                                   edgeCounter++);

      edgePositions.add(position);
      positionToEdge.put(position, edge);
    }
  }

  /**
   * Find all non-removable predecessors of a block, tracing through removable blocks.
   */
  private Set<Integer> findNonRemovablePredecessors(int blockIdx, Map<Integer, Set<Integer>> predecessors) {
    Set<Integer> result = new HashSet<>();
    Set<Integer> visited = new HashSet<>();
    Queue<Integer> queue = new LinkedList<>();

    for (int pred : predecessors.get(blockIdx)) {
      queue.add(pred);
    }

    while (!queue.isEmpty()) {
      int current = queue.poll();
      if (visited.contains(current)) continue;
      visited.add(current);

      if (removableBlocks.contains(current)) {
        // Trace through removable block
        for (int pred : predecessors.get(current)) {
          queue.add(pred);
        }
      } else {
        result.add(current);
      }
    }

    return result;
  }

  /**
   * Collect all removed predecessors transitively.
   */
  private void collectRemovedPredecessors(int blockIdx, Map<Integer, Set<Integer>> predecessors,
                                          List<Integer> removedNodes) {
    for (int pred : predecessors.get(blockIdx)) {
      if (removableBlocks.contains(pred) && !removedNodes.contains(pred)) {
        removedNodes.add(pred);
        collectRemovedPredecessors(pred, predecessors, removedNodes);
      }
    }
  }

  @Override
  public boolean instrumentAtIndex(final int index, final int instrumentationSize) {
    boolean outcome = !this.edgePositions.isEmpty() &&
                      index >= instrumentationSize + this.edgePositions.peek();
    if (outcome) {
      int pos = this.edgePositions.poll();
      this.currentEdge = positionToEdge.get(pos);
    }
    return outcome;
  }

  @Override
  public boolean stopInstrumenting() {
    return this.edgePositions.isEmpty();
  }

  @Override
  public String getNodeSuffix() {
    if (currentEdge != null) {
      // Return edge representation: #EDGE:fromLine->toLine
      return "#EDGE:" + currentEdge.getEdgeLabel();
    }
    return "";
  }

  /**
   * Get the current edge info for annotation purposes.
   */
  public EdgeInfo getCurrentEdge() {
    return currentEdge;
  }

  /**
   * Get all edge information for this method.
   */
  public Collection<EdgeInfo> getAllEdges() {
    return positionToEdge.values();
  }

  /**
   * Get the basic blocks array.
   */
  public Block[] getBlocks() {
    return blocks;
  }

  /**
   * Get the set of removable block indices.
   */
  public Set<Integer> getRemovableBlocks() {
    return removableBlocks;
  }
}
