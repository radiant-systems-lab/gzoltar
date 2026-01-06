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
package com.gzoltar.core.instr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jacoco.core.internal.ContentTypeDetector;
import org.jacoco.core.internal.Pack200Streams;
import org.jacoco.core.internal.instr.SignatureRemover;
import com.gzoltar.core.AgentConfigs;
import com.gzoltar.core.instr.granularity.GranularityLevel;
import com.gzoltar.core.instr.pass.IPass;
import com.gzoltar.core.instr.pass.CoveragePass;
import com.gzoltar.core.instr.pass.ASMEdgeInstrumentor;
import com.gzoltar.core.runtime.Collector;
import com.gzoltar.core.runtime.ProbeGroup;
import com.gzoltar.core.util.MD5;
import javassist.ClassPool;
import javassist.CtClass;

/**
 * Several APIs to instrument Java class definitions for coverage tracing.
 */
public class Instrumenter {

  private final IPass[] passes;

  private final SignatureRemover signatureRemover;

  private final GranularityLevel granularityLevel;

  private final ASMEdgeInstrumentor asmEdgeInstrumentor;

  /**
   *
   * @param agentConfigs
   */
  public Instrumenter(final AgentConfigs agentConfigs) {
    this.granularityLevel = agentConfigs.getGranularity();
    this.passes = new IPass[] {
        //new TestFilterPass(), // do not instrument test classes/cases
        new CoveragePass(agentConfigs)
    };
    this.signatureRemover = new SignatureRemover();

    // Initialize ASM edge instrumentor for EDGE granularity
    if (this.granularityLevel == GranularityLevel.EDGE) {
      this.asmEdgeInstrumentor = new ASMEdgeInstrumentor();
    } else {
      this.asmEdgeInstrumentor = null;
    }
  }

  /**
   * Determines whether signatures should be removed from JAR files. This is typically necessary as
   * instrumentation modifies the class files and therefore invalidates existing JAR signatures.
   * Default is <code>true</code>.
   *
   * @param flag <code>true</code> if signatures should be removed
   */
  public void setRemoveSignatures(final boolean flag) {
    this.signatureRemover.setActive(flag);
  }

  /**
   *
   * @param classfileBuffer
   * @return
   * @throws Exception
   */
  public byte[] instrument(final byte[] classfileBuffer) throws Exception {
    // For EDGE granularity, use ASM-based instrumentation
    if (this.granularityLevel == GranularityLevel.EDGE && this.asmEdgeInstrumentor != null) {
      return this.instrumentWithASM(classfileBuffer);
    }
    return this.instrument(new ByteArrayInputStream(classfileBuffer));
  }

  /**
   * Instrument using ASM for EDGE granularity.
   * This method bypasses Javassist and uses ASM directly for precise edge probe placement.
   */
  private byte[] instrumentWithASM(final byte[] classfileBuffer) throws Exception {
    // First, use Javassist to add field and init method (standard GZoltar infrastructure)
    CtClass cc = ClassPool.getDefault().makeClass(new ByteArrayInputStream(classfileBuffer));

    // Run the standard passes to add $gzoltarData field and $gzoltarInit method
    for (IPass p : this.passes) {
      switch (p.transform(cc)) {
        case REJECT:
          cc.detach();
          return null;
        case ACCEPT:
        default:
          continue;
      }
    }

    // Get the bytecode with field and init method added
    byte[] preparedBytecode = cc.toBytecode();
    cc.detach();

    // Now use ASM to insert edge probes
    // Create a probe group for this class
    String hash = MD5.calculateHash(classfileBuffer);
    CtClass ccForProbeGroup = ClassPool.getDefault().makeClass(new ByteArrayInputStream(classfileBuffer));
    ProbeGroup probeGroup = new ProbeGroup(hash, ccForProbeGroup);
    ccForProbeGroup.detach();

    // Instrument with ASM edge instrumentor
    byte[] instrumentedBytecode = this.asmEdgeInstrumentor.instrument(preparedBytecode, probeGroup);

    // Register the probe group
    Collector.instance().regiterProbeGroup(probeGroup);

    return instrumentedBytecode;
  }

  /**
   *
   * @param sourceStream
   * @return
   * @throws Exception
   */
  public byte[] instrument(final InputStream sourceStream) throws Exception {
    // For EDGE granularity, read bytes and use ASM
    if (this.granularityLevel == GranularityLevel.EDGE && this.asmEdgeInstrumentor != null) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int len;
      while ((len = sourceStream.read(buffer)) != -1) {
        baos.write(buffer, 0, len);
      }
      return this.instrumentWithASM(baos.toByteArray());
    }

    CtClass cc = ClassPool.getDefault().makeClassIfNew(sourceStream);
    return this.instrument(cc);
  }

  /**
   *
   * @param cc
   * @return
   * @throws Exception
   */
  public byte[] instrument(final CtClass cc) throws Exception {
    for (IPass p : this.passes) {
      switch (p.transform(cc)) {
        case REJECT:
          cc.detach();
          return null;
        case ACCEPT:
        default:
          continue;
      }
    }

    byte[] bytecode = cc.toBytecode();
    return bytecode;
  }

  /**
   * Get edge records for coverage recovery (only available for EDGE granularity).
   */
  public java.util.List<ASMEdgeInstrumentor.EdgeRecord> getEdgeRecords() {
    if (this.asmEdgeInstrumentor != null) {
      return this.asmEdgeInstrumentor.getAllEdgeRecords();
    }
    return new java.util.ArrayList<>();
  }

  /**
   * Get all unique node names (only available for EDGE granularity).
   */
  public java.util.Set<String> getAllNodes() {
    if (this.asmEdgeInstrumentor != null) {
      return this.asmEdgeInstrumentor.getAllNodes();
    }
    return new java.util.HashSet<>();
  }

  /**
   * Creates a instrumented version of the given resource depending on its type. Class files and the
   * content of archive files (.zip, .jar) are instrumented. All other files are copied without
   * modification.
   *
   * @param input stream to contents from
   * @param output stream to write the instrumented version of the contents
   * @return number of instrumented classes
   * @throws Exception if reading data from the stream fails or a class cannot be instrumented
   */
  public int instrumentToFile(final InputStream input, final OutputStream output) throws Exception {
    final ContentTypeDetector detector = new ContentTypeDetector(input);
    switch (detector.getType()) {
      case ContentTypeDetector.CLASSFILE:
        output.write(this.instrument(detector.getInputStream()));
        return 1;
      case ContentTypeDetector.GZFILE:
        return this.instrumentGzip(detector.getInputStream(), output);
      case ContentTypeDetector.PACK200FILE:
        return this.instrumentPack200(detector.getInputStream(), output);
      case ContentTypeDetector.ZIPFILE:
        return this.instrumentZip(detector.getInputStream(), output);
      case ContentTypeDetector.UNKNOWN:
      default:
        this.copy(detector.getInputStream(), output);
        return 0;
    }
  }

  public int instrument(File source, File dest) throws Exception {
    dest.getParentFile().mkdirs();
    final InputStream input = new FileInputStream(source);
    try {
      final OutputStream output = new FileOutputStream(dest);
      try {
        return this.instrumentToFile(input, output);
      } finally {
        output.close();
      }
    } catch (Exception e) {
      throw e;
    } finally {
      input.close();
    }
  }

  public int instrumentRecursively(File source, File dest)
      throws Exception {
    int numInstrumentedClasses = 0;

    if (source.isDirectory()) {
      ClassPool.getDefault().appendClassPath(source.getAbsolutePath());
      for (final File child : source.listFiles()) {
        numInstrumentedClasses += this.instrumentRecursively(child, new File(dest, child.getName()));
      }
    } else {
      numInstrumentedClasses += this.instrument(source, dest);
    }

    return numInstrumentedClasses;
  }

  private int instrumentGzip(final InputStream input, final OutputStream output) throws Exception {
    final GZIPInputStream gzipInputStream = new GZIPInputStream(input);
    final GZIPOutputStream gzipOutputStream = new GZIPOutputStream(output);
    final int count = this.instrumentToFile(gzipInputStream, gzipOutputStream);
    gzipOutputStream.finish();
    return count;
  }

  private int instrumentPack200(final InputStream input, final OutputStream output)
      throws Exception {
    final InputStream unpackedInput = Pack200Streams.unpack(input);
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    final int count = this.instrumentToFile(unpackedInput, buffer);
    Pack200Streams.pack(buffer.toByteArray(), output);
    return count;
  }

  private int instrumentZip(final InputStream input, final OutputStream output) throws Exception {
    final ZipInputStream zipInputStream = new ZipInputStream(input);
    final ZipOutputStream zipOutputStream = new ZipOutputStream(output);
    ZipEntry entry;
    int count = 0;

    while ((entry = zipInputStream.getNextEntry()) != null) {
      final String entryName = entry.getName();
      if (this.signatureRemover.removeEntry(entryName)) {
        continue;
      }

      zipOutputStream.putNextEntry(new ZipEntry(entryName));
      if (!this.signatureRemover.filterEntry(entryName, zipInputStream, zipOutputStream)) {
        count += this.instrumentToFile(zipInputStream, zipOutputStream);
      }
      zipOutputStream.closeEntry();
    }
    zipOutputStream.finish();

    return count;
  }

  private void copy(final InputStream input, final OutputStream output) throws IOException {
    final byte[] buffer = new byte[1024];
    int len;
    while ((len = input.read(buffer)) != -1) {
      output.write(buffer, 0, len);
    }
  }
}
