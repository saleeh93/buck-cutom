/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import static com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import static com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;

import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuilder;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class AndroidBinaryBuilder extends AbstractBuilder<AndroidBinaryDescription.Arg> {

  private AndroidBinaryBuilder(BuildTarget target) {
    super(new AndroidBinaryDescription(JavacOptions.DEFAULTS, Optional.<Path>absent()), target);
  }

  public static AndroidBinaryBuilder createBuilder(BuildTarget buildTarget) {
    return new AndroidBinaryBuilder(buildTarget);
  }

  public AndroidBinaryBuilder setManifest(SourcePath manifest) {
    arg.manifest = manifest;
    return this;
  }

  public AndroidBinaryBuilder setTarget(String target) {
    arg.target = target;
    return this;
  }

  public AndroidBinaryBuilder setOriginalDeps(ImmutableSortedSet<BuildRule> originalDeps) {
    arg.deps = Optional.of(originalDeps);
    return this;
  }

  public AndroidBinaryBuilder setKeystore(Keystore keystore) {
    arg.keystore = keystore;
    amend(arg.deps, keystore);
    return this;
  }

  public AndroidBinaryBuilder setShouldSplitDex(boolean shouldSplitDex) {
    arg.useSplitDex = Optional.of(shouldSplitDex);
    return this;
  }

  public AndroidBinaryBuilder setDexCompression(DexStore dexStore) {
    arg.dexCompression = Optional.of(dexStore);
    return this;
  }

  public AndroidBinaryBuilder setLinearAllocHardLimit(long limit) {
    arg.linearAllocHardLimit = Optional.of(limit);
    return this;
  }

  public AndroidBinaryBuilder setPrimaryDexScenarioOverflowAllowed(boolean allowed) {
    arg.primaryDexScenarioOverflowAllowed = Optional.of(allowed);
    return this;
  }

  public AndroidBinaryBuilder setBuildTargetsToExcludeFromDex(
      Set<BuildTarget> buildTargetsToExcludeFromDex) {
    arg.noDx = Optional.of(buildTargetsToExcludeFromDex);
    return this;
  }

  public AndroidBinaryBuilder setProguardConfig(Optional<SourcePath> proguardConfig) {
    arg.proguardConfig = proguardConfig;
    return this;
  }

  public AndroidBinaryBuilder setResourceCompressionMode(
      ResourceCompressionMode resourceCompressionMode) {
    arg.resourceCompression = Optional.of(resourceCompressionMode.toString());
    return this;
  }

  public AndroidBinaryBuilder setResourceFilter(ResourceFilter resourceFilter) {
    List<String> rawFilters = ImmutableList.copyOf(resourceFilter.getFilter());
    arg.resourceFilter = Optional.of(rawFilters);
    return this;
  }
}
