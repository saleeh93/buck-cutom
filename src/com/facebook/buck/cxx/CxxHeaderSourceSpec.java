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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class CxxHeaderSourceSpec {

  private final ImmutableMap<Path, SourcePath> cxxHeaders;
  private final ImmutableList<CxxSource> cxxSources;

  public CxxHeaderSourceSpec(
      ImmutableMap<Path, SourcePath> cxxHeaders,
      ImmutableList<CxxSource> cxxSources) {

    this.cxxHeaders = Preconditions.checkNotNull(cxxHeaders);
    this.cxxSources = Preconditions.checkNotNull(cxxSources);
  }

  public ImmutableMap<Path, SourcePath> getCxxHeaders() {
    return cxxHeaders;
  }

  public ImmutableList<CxxSource> getCxxSources() {
    return cxxSources;
  }

}
