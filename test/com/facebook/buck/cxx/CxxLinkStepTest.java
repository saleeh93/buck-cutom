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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxLinkStepTest {

  @Test
  public void cxxLinkStepUsesCorrectCommand() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    // Setup some dummy values for inputs to the CxxLinkStep
    Path linker = Paths.get("linker");
    Path output = Paths.get("output");
    ImmutableList<String> args = ImmutableList.of(
        "-rpath",
        "hello",
        "a.o",
        "libb.a");

    // Create our CxxLinkStep to test.
    CxxLinkStep cxxLinkStep = new CxxLinkStep(linker, output, args);

    // Verify it uses the expected command.
    ImmutableList<String> expected = ImmutableList.<String>builder()
        .add(linker.toString())
        .add("-o", output.toString())
        .addAll(args)
        .build();
    ImmutableList<String> actual = cxxLinkStep.getShellCommand(context);
    assertEquals(expected, actual);
  }

}
