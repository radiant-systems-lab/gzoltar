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
package com.gzoltar.core.spectrum;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.commons.lang3.tuple.Pair;
import org.jacoco.core.internal.data.CompactDataOutput;
import com.gzoltar.core.model.EdgeAnnotation;
import com.gzoltar.core.model.EdgeAnnotationRegistry;
import com.gzoltar.core.model.Transaction;
import com.gzoltar.core.util.SerialisationIdentifiers;

/**
 * Serialization of a spectrum instance into binary streams.
 */
public class SpectrumWriter {

  private final CompactDataOutput out;

  /**
   * Creates a new writer based on the given output stream. Depending on the nature of the
   * underlying stream output should be buffered as most data is written in single bytes.
   * 
   * @param output binary stream to write execution data to
   * @throws IOException if the header can't be written
   */
  public SpectrumWriter(final OutputStream output)
      throws IOException {
    this.out = new CompactDataOutput(output);
    this.writeHeader();
  }

  /**
   * Writes an file header to identify the stream and its protocol version.
   * 
   * @throws IOException if the header can't be written
   */
  private void writeHeader() throws IOException {
    this.out.writeByte(SerialisationIdentifiers.BLOCK_HEADER);
    this.out.writeChar(SerialisationIdentifiers.MAGIC_NUMBER);
    this.out.writeChar(SerialisationIdentifiers.FORMAT_VERSION);
  }

  /**
   * Serializes a spectrum instance into binary streams.
   *
   * @param spectrum
   * @throws IOException if the data can't be written
   */
  public void writeSpectrum(final ISpectrum spectrum) throws IOException {
    // Write edge annotations if available (for EDGE granularity)
    this.writeEdgeAnnotations();

    // Write transactions
    for (final Transaction transaction : spectrum.getTransactions()) {
      this.writeTransaction(transaction);
    }
    this.out.close();
  }

  /**
   * Writes edge annotations to the output stream (for EDGE granularity).
   *
   * @throws IOException if the data can't be written
   */
  private void writeEdgeAnnotations() throws IOException {
    EdgeAnnotationRegistry registry = EdgeAnnotationRegistry.getInstance();
    Collection<EdgeAnnotation> annotations = registry.getAllAnnotations();
    if (annotations.isEmpty()) {
      return;
    }

    this.out.writeByte(SerialisationIdentifiers.BLOCK_EDGE_ANNOTATION);
    this.out.writeVarInt(annotations.size());

    for (EdgeAnnotation annotation : annotations) {
      this.out.writeVarInt(annotation.getEdgeId());
      this.out.writeVarInt(annotation.getProbeId());
      this.out.writeUTF(annotation.getMethodKey());
      this.out.writeUTF(annotation.getFromNode());
      this.out.writeUTF(annotation.getToNode());

      // Write removed nodes
      List<String> removedNodes = annotation.getRemovedNodes();
      this.out.writeVarInt(removedNodes.size());
      for (String node : removedNodes) {
        this.out.writeUTF(node);
      }

      // Write covered lines
      List<Integer> coveredLines = annotation.getCoveredLines();
      this.out.writeVarInt(coveredLines.size());
      for (Integer line : coveredLines) {
        this.out.writeVarInt(line);
      }
    }
  }

  /**
   * Serializes a transaction instance into binary streams.
   * 
   * @param transaction
   * @throws IOException
   */
  public void writeTransaction(final Transaction transaction) throws IOException {
    TransactionSerialize.serialize(this.out, transaction);
  }

  /**
   * 
   */
  private static final class TransactionSerialize {

    /**
     * Serialises an instance of {@link com.gzoltar.core.model.Transaction}.
     * 
     * @param out binary stream to write bytes to
     * @param transaction
     * @throws IOException
     */
    public static void serialize(final CompactDataOutput out, final Transaction transaction)
        throws IOException {
      if (transaction.hasActivations()) {
        out.writeByte(SerialisationIdentifiers.BLOCK_TRANSACTION);
        out.writeUTF(transaction.getName());

        Map<String, Pair<String, boolean[]>> activity = transaction.getActivity();
        out.writeVarInt(activity.size());

        for (Entry<String, Pair<String, boolean[]>> entry : activity.entrySet()) {
          out.writeUTF(entry.getKey()); // hash
          out.writeUTF(entry.getValue().getLeft()); // name
          out.writeBooleanArray(entry.getValue().getRight()); // hitArray
        }

        out.writeUTF(transaction.getTransactionOutcome().name());
        out.writeLong(transaction.getRuntime());
        out.writeUTF(transaction.getStackTrace());
      }
    }
  }

}
