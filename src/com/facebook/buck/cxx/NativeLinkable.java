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

import com.facebook.buck.rules.BuildRuleType;
import com.google.common.base.Function;

/**
 * Interface for {@link com.facebook.buck.rules.BuildRule} objects (e.g. C++ libraries) which can
 * contribute to the top-level link of a native binary (e.g. C++ binary).
 */
public interface NativeLinkable {

  /**
   * A helper function object that grabs the {@link NativeLinkableInput} object from a
   * {@link NativeLinkable}.
   */
  final Function<NativeLinkable, NativeLinkableInput> GET_NATIVE_LINKABLE_INPUT =
      new Function<NativeLinkable, NativeLinkableInput>() {
        @Override
        public NativeLinkableInput apply(NativeLinkable input) {
          return input.getNativeLinkableInput();
        }
      };

  final BuildRuleType NATIVE_LINKABLE_TYPE = new BuildRuleType("link");

  NativeLinkableInput getNativeLinkableInput();

}
