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

package com.facebook.buck.python;

import com.facebook.buck.python.PythonLibraryDescription.Arg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class PythonLibraryDescription implements Description<Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("python_library");

  @SuppressFieldNotInitialized
  public static class Arg implements ConstructorArg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<ImmutableSortedSet<BuildRule>> deps;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> PythonLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return new PythonLibrary(
        params,
        PythonUtil.toModuleMap(
            params.getBuildTarget(),
            "srcs",
            params.getBuildTarget().getBasePath(),
            args.srcs.or(ImmutableSortedSet.<SourcePath>of())),
        PythonUtil.toModuleMap(
            params.getBuildTarget(),
            "resources",
            params.getBuildTarget().getBasePath(),
            args.resources.or(ImmutableSortedSet.<SourcePath>of())));
  }

}
