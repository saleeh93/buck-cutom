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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonBinaryTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  private RuleKey.Builder.RuleKeyPair getRuleKeyForModuleLayout(
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      String main, Path mainSrc,
      String mod1, Path src1,
      String mod2, Path src2) throws IOException {

    // The top-level python binary that lists the above libraries as deps.
    PythonBinary binary = new PythonBinary(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(
            BuildTargetFactory.newInstance("//:bin")),
        Paths.get("dummy_path_to_pex"),
        Paths.get("main.py"),
        new PythonPackageComponents(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(main), new PathSourcePath(mainSrc),
                Paths.get(mod1), new PathSourcePath(src1),
                Paths.get(mod2), new PathSourcePath(src2)),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of()));

    // Calculate and return the rule key.
    RuleKey.Builder builder = ruleKeyBuilderFactory.newInstance(binary);
    binary.appendToRuleKey(builder);
    return builder.build();
  }

  @Test
  public void testRuleKeysFromModuleLayouts() throws IOException {

    // Create two different sources, which we'll swap in as different modules.
    Path main = tmpDir.newFile().toPath();
    Files.write(main, "main".getBytes(Charsets.UTF_8));
    Path source1 = tmpDir.newFile().toPath();
    Files.write(source1, "hello world".getBytes(Charsets.UTF_8));
    Path source2 = tmpDir.newFile().toPath();
    Files.write(source2, "goodbye world".getBytes(Charsets.UTF_8));

    // Setup a rulekey builder factory.
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    main.toString(), Strings.repeat("a", 40),
                    source1.toString(), Strings.repeat("b", 40),
                    source2.toString(), Strings.repeat("c", 40))));

    // Calculate the rule keys for the various ways we can layout the source and modules
    // across different python libraries.
    RuleKey.Builder.RuleKeyPair pair1 = getRuleKeyForModuleLayout(
        ruleKeyBuilderFactory,
        "main.py", main,
        "module/one.py", source1,
        "module/two.py", source2);
    RuleKey.Builder.RuleKeyPair pair2 = getRuleKeyForModuleLayout(
        ruleKeyBuilderFactory,
        "main.py", main,
        "module/two.py", source2,
        "module/one.py", source1);
    RuleKey.Builder.RuleKeyPair pair3 = getRuleKeyForModuleLayout(
        ruleKeyBuilderFactory,
        "main.py", main,
        "module/one.py", source2,
        "module/two.py", source1);
    RuleKey.Builder.RuleKeyPair pair4 = getRuleKeyForModuleLayout(
        ruleKeyBuilderFactory,
        "main.py", main,
        "module/two.py", source1,
        "module/one.py", source2);

    // Make sure only cases where the actual module layouts are different result
    // in different rules keys.
    assertEquals(pair1.getTotalRuleKey(), pair2.getTotalRuleKey());
    assertEquals(pair3.getTotalRuleKey(), pair4.getTotalRuleKey());
    assertNotEquals(pair1.getTotalRuleKey(), pair3.getTotalRuleKey());
  }

}
