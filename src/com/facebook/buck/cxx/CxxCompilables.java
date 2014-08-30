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

import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCompilables {

  private CxxCompilables() {}

  public static final Path DEFAULT_CC_COMPILER = Paths.get("/usr/bin/gcc");
  public static final Path DEFAULT_CXX_COMPILER = Paths.get("/usr/bin/g++");

  /**
   * Source files that can be preprocessed and compiled.
   */
  public static final ImmutableSet<String> SOURCE_EXTENSIONS =
      ImmutableSet.of("c", "cc", "cpp", "cxx", "m", "mm", "C", "cp", "CPP", "c++");

  /**
   * Header files that can be included in preprocessing.
   */
  public static final ImmutableSet<String> HEADER_EXTENSIONS =
      ImmutableSet.of("h", "hh", "hpp", "hxx", "H", "hp", "HPP", "h++", "tcc");

}
