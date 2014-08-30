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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BuildTargetsTest {

  @Test
  public void testCreateFlavoredBuildTarget() {
    BuildTarget fooBar = BuildTarget.builder("//foo", "bar").build();
    BuildTarget fooBarBaz = BuildTargets.createFlavoredBuildTarget(fooBar, new Flavor("baz"));
    assertTrue(fooBarBaz.isFlavored());
    assertEquals("//foo:bar#baz", fooBarBaz.getFullyQualifiedName());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCreateFlavoredBuildTargetRejectsFlavoredBuildTarget() {
    BuildTarget fooBarBaz = BuildTarget.builder("//foo", "bar").setFlavor("baz").build();
    BuildTargets.createFlavoredBuildTarget(fooBarBaz, new Flavor("buzz"));
  }

  @Test
  public void testExtendFlavoredBuildTargetOnFlavorlessTarget() {
    BuildTarget fooBar = BuildTarget.builder("//foo", "bar").build();
    BuildTarget fooBarBaz = BuildTargets.extendFlavoredBuildTarget(fooBar, new Flavor("baz"));
    assertTrue(fooBarBaz.isFlavored());
    assertEquals("//foo:bar#baz", fooBarBaz.getFullyQualifiedName());
  }

  @Test
  public void testExtendFlavoredBuildTargetOnFlavoredTarget() {
    BuildTarget fooBar = BuildTarget.builder("//foo", "bar")
        .setFlavor(new Flavor("hello"))
        .build();
    BuildTarget fooBarBaz = BuildTargets.extendFlavoredBuildTarget(fooBar, new Flavor("baz"));
    assertTrue(fooBarBaz.isFlavored());
    assertEquals("//foo:bar#hello-baz", fooBarBaz.getFullyQualifiedName());
  }

}
