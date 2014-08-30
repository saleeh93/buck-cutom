/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.dalvik;

import com.facebook.buck.java.classes.AbstractFileLike;
import com.facebook.buck.java.classes.ClasspathTraversal;
import com.facebook.buck.java.classes.ClasspathTraverser;
import com.facebook.buck.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.java.classes.FileLike;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Alternative to {@link DefaultZipSplitter} that uses estimates from {@link DalvikStatsTool}
 * to determine how many classes to pack into a dex.
 * <p>
 * It does three passes through the .class files:
 * <ul>
 *   <li>
 *     During the first pass, it uses the {@code requiredInPrimaryZip} predicate to filter the set
 *     of classes that <em>must</em> be included in the primary dex. These classes are added to
 *     the primary zip.
 *   </li>
 *   <li>
 *     During the second pass, it uses the {@code wantedInPrimaryZip} list to find classes that
 *     were not included in the first pass but that should still be in the primary zip for
 *     performance reasons, and adds them to the primary zip.
 *   </li>
 *   <li>
 *     During the third pass, classes that were not matched during the earlier passes are added
 *     to zips as space allows. This is a simple, greedy algorithm.
 *   </li>
 * </ul>
 */
public class DalvikAwareZipSplitter implements ZipSplitter {

  private final ProjectFilesystem filesystem;
  private final Set<Path> inFiles;
  private final File outPrimary;
  private final Predicate<String> requiredInPrimaryZip;
  private final Set<String> wantedInPrimaryZip;
  private final File reportDir;
  private final long linearAllocLimit;
  private final DalvikStatsCache dalvikStatsCache;
  private final DexSplitStrategy dexSplitStrategy;

  private final MySecondaryDexHelper secondaryDexWriter;
  private DalvikAwareOutputStreamHelper primaryOut;

  /**
   * @see ZipSplitterFactory#newInstance(ProjectFilesystem, Set, File, File, String, Predicate,
   *     com.facebook.buck.dalvik.ZipSplitter.DexSplitStrategy,
   *     com.facebook.buck.dalvik.ZipSplitter.CanaryStrategy, File)
   */
  private DalvikAwareZipSplitter(
      ProjectFilesystem filesystem,
      Set<Path> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long linearAllocLimit,
      Predicate<String> requiredInPrimaryZip,
      Set<String> wantedInPrimaryZip,
      DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    if (linearAllocLimit <= 0) {
      throw new HumanReadableException("linear_alloc_hard_limit must be greater than zero.");
    }
    this.filesystem = Preconditions.checkNotNull(filesystem);
    this.inFiles = ImmutableSet.copyOf(inFiles);
    this.outPrimary = Preconditions.checkNotNull(outPrimary);
    this.secondaryDexWriter =
        new MySecondaryDexHelper(outSecondaryDir, secondaryPattern, canaryStrategy);
    this.requiredInPrimaryZip = Preconditions.checkNotNull(requiredInPrimaryZip);
    this.wantedInPrimaryZip = ImmutableSet.copyOf(wantedInPrimaryZip);
    this.reportDir = reportDir;
    this.dexSplitStrategy = Preconditions.checkNotNull(dexSplitStrategy);
    this.linearAllocLimit = linearAllocLimit;
    this.dalvikStatsCache = new DalvikStatsCache();
  }

  public static DalvikAwareZipSplitter splitZip(
      ProjectFilesystem filesystem,
      Set<Path> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long linearAllocLimit,
      Predicate<String> requiredInPrimaryZip,
      Set<String> wantedInPrimaryZip,
      DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    return new DalvikAwareZipSplitter(
        filesystem,
        inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        linearAllocLimit,
        requiredInPrimaryZip,
        wantedInPrimaryZip,
        dexSplitStrategy,
        canaryStrategy,
        reportDir);
  }

  @Override
  public List<File> execute() throws IOException {
    ClasspathTraverser classpathTraverser = new DefaultClasspathTraverser();

    // Start out by writing the primary zip and recording which entries were added to it.
    primaryOut = newZipOutput(outPrimary);
    secondaryDexWriter.reset();

    final ImmutableMap.Builder<String, FileLike> entriesBuilder = ImmutableMap.builder();

    // Iterate over all of the inFiles and add all entries that match the requiredInPrimaryZip
    // predicate.
    classpathTraverser.traverse(new ClasspathTraversal(inFiles, filesystem) {
      @Override
      public void visit(FileLike entry) throws IOException {
        String relativePath = entry.getRelativePath();
        if (requiredInPrimaryZip.apply(relativePath)) {
          primaryOut.putEntry(entry);
        } else if (wantedInPrimaryZip.contains(relativePath)) {
          entriesBuilder.put(relativePath, new BufferedFileLike(entry));
        }
      }
    });

    // Put as many of the items wanted in the primary dex as we can into the primary dex.
    ImmutableMap<String, FileLike> entries = entriesBuilder.build();
    for (String wanted : wantedInPrimaryZip) {
      FileLike entry = entries.get(wanted);
      if ((entry != null) && !primaryOut.containsEntry(entry) && primaryOut.canPutEntry(entry)) {
        primaryOut.putEntry(entry);
      }
    }

    // Now that all of the required entries have been added to the primary zip, fill the rest of
    // the zip up with the remaining entries.
    classpathTraverser.traverse(new ClasspathTraversal(inFiles, filesystem) {
      @Override
      public void visit(FileLike entry) throws IOException {
        if (primaryOut.containsEntry(entry)) {
          return;
        }

        // Even if we have started writing a secondary dex, we still check if there is any leftover
        // room in the primary dex for the current entry in the traversal.
        if (dexSplitStrategy == DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE &&
            primaryOut.canPutEntry(entry)) {
          primaryOut.putEntry(entry);
        } else {
          secondaryDexWriter.getOutputToWriteTo(entry).putEntry(entry);
        }
      }
    });

    primaryOut.close();
    secondaryDexWriter.close();
    return secondaryDexWriter.getFiles();
  }

  private DalvikAwareOutputStreamHelper newZipOutput(File file) throws FileNotFoundException {
    return new DalvikAwareOutputStreamHelper(file, linearAllocLimit, reportDir, dalvikStatsCache);
  }

  private class MySecondaryDexHelper
      extends SecondaryDexHelper<DalvikAwareOutputStreamHelper> {

    MySecondaryDexHelper(
        File outSecondaryDir,
        String secondaryPattern,
        CanaryStrategy canaryStrategy) {
      super(outSecondaryDir, secondaryPattern, canaryStrategy);
    }

    @Override
    protected DalvikAwareOutputStreamHelper newZipOutput(File file) throws IOException {
      return DalvikAwareZipSplitter.this.newZipOutput(file);
    }
  }

  private static class BufferedFileLike extends AbstractFileLike {
    private final File container;
    private final String relativePath;
    private final byte[] contents;

    public BufferedFileLike(FileLike original) throws IOException {
      this.container = original.getContainer();
      this.relativePath = original.getRelativePath();

      try (InputStream stream = original.getInput()) {
        contents = ByteStreams.toByteArray(stream);
      }
    }

    @Override
    public File getContainer() {
      return container;
    }

    @Override
    public String getRelativePath() {
      return relativePath;
    }

    @Override
    public long getSize() {
      return contents.length;
    }

    @Override
    public InputStream getInput() throws IOException {
      return new ByteArrayInputStream(contents);
    }
  }
}
