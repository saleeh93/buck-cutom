/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.command.Build;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExceptionWithHumanReadableMessage;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

public class BuildCommand extends AbstractCommandRunner<BuildCommandOptions> {

  @Nullable private Build build;

  private ImmutableSet<BuildTarget> buildTargets = ImmutableSet.of();

  public BuildCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  BuildCommandOptions createOptions(BuckConfig buckConfig) {
    return new BuildCommandOptions(buckConfig);
  }

  @Override
  @SuppressWarnings("PMD.PrematureDeclaration")
  int runCommandWithOptionsInternal(BuildCommandOptions options)
      throws IOException, InterruptedException {
    // Create artifact cache to initialize Cassandra connection, if appropriate.
    ArtifactCache artifactCache = getArtifactCache();


    try {
      buildTargets = getBuildTargets(options.getArgumentsFormattedAsBuildTargets());
    } catch (NoSuchBuildTargetException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    if (buildTargets.isEmpty()) {
      console.printBuildFailure("Must specify at least one build target.");

      // If there are aliases defined in .buckconfig, suggest that the user
      // build one of them. We show the user only the first 10 aliases.
      ImmutableSet<String> aliases = options.getBuckConfig().getAliases();
      if (!aliases.isEmpty()) {
        console.getStdErr().println(String.format(
            "Try building one of the following targets:\n%s",
            Joiner.on(' ').join(Iterators.limit(aliases.iterator(), 10))));
      }
      return 1;
    }

    // Post the build started event, setting it to the Parser recorded start time if appropriate.
    if (getParser().getParseStartTime().isPresent()) {
      getBuckEventBus().post(
          BuildEvent.started(buildTargets),
          getParser().getParseStartTime().get());
    } else {
      getBuckEventBus().post(BuildEvent.started(buildTargets));
    }

    // Parse the build files to create a ActionGraph.
    ActionGraph actionGraph;
    try {
      actionGraph = getParser().parseBuildFilesForTargets(buildTargets,
          options.getDefaultIncludes(),
          getBuckEventBus(),
          console,
          environment);
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // Calculate and post the number of rules that need to built.
    int numRules = getNumRulesToBuild(buildTargets, actionGraph);
    getBuckEventBus().post(BuildEvent.ruleCountCalculated(buildTargets, numRules));

    // Create and execute the build.
    build = options.createBuild(
        options.getBuckConfig(),
        actionGraph,
        getProjectFilesystem(),
        getAndroidDirectoryResolver(),
        getBuildEngine(),
        artifactCache,
        console,
        getBuckEventBus(),
        Optional.<TargetDevice>absent(),
        getCommandRunnerParams().getPlatform(),
        getCommandRunnerParams().getEnvironment(),
        getCommandRunnerParams().getObjectMapper());
    int exitCode = 0;
    try {
      exitCode = executeBuildAndPrintAnyFailuresToConsole(buildTargets, build, console);
    } finally {
      build.close(); // Can't use try-with-resources as build is returned by getBuild.
    }
    getBuckEventBus().post(BuildEvent.finished(buildTargets, exitCode));

    return exitCode;
  }

  private static int getNumRulesToBuild(
      ImmutableSet<BuildTarget> buildTargets,
      final ActionGraph actionGraph) {
    Set<BuildRule> baseBuildRules = FluentIterable
        .from(buildTargets)
        .transform(new Function<HasBuildTarget, BuildRule>() {
                     @Override
                     public BuildRule apply(HasBuildTarget hasBuildTarget) {
                       return actionGraph.findBuildRuleByTarget(hasBuildTarget.getBuildTarget());
                     }
                   })
        .toSet();

    Set<BuildRule> allBuildRules = Sets.newHashSet();
    for (BuildRule rule : baseBuildRules) {
      addTransitiveDepsForRule(rule, allBuildRules);
    }
    allBuildRules.addAll(baseBuildRules);
    return allBuildRules.size();
  }

  private static void addTransitiveDepsForRule(
      BuildRule buildRule,
      Set<BuildRule> transitiveDeps) {
    ImmutableSortedSet<BuildRule> deps = buildRule.getDeps();
    if (deps.isEmpty()) {
      return;
    }
    for (BuildRule dep : deps) {
      if (!transitiveDeps.contains(dep)) {
        transitiveDeps.add(dep);
        addTransitiveDepsForRule(dep, transitiveDeps);
      }
    }
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  static int executeBuildAndPrintAnyFailuresToConsole(
      Iterable<? extends HasBuildTarget> buildTargetsToBuild,
      Build build,
      Console console) throws InterruptedException {
    final ActionGraph actionGraph = build.getActionGraph();
    // It is important to use this logic to determine the set of rules to build rather than
    // build.getActionGraph().getNodesWithNoIncomingEdges() because, due to graph enhancement,
    // there could be disconnected subgraphs in the DependencyGraph that we do not want to build.
    Set<BuildRule> rulesToBuild = FluentIterable
        .from(buildTargetsToBuild)
        .transform(new Function<HasBuildTarget, BuildRule>() {
          @Override
          public BuildRule apply(HasBuildTarget hasBuildTarget) {
            return actionGraph.findBuildRuleByTarget(hasBuildTarget.getBuildTarget());
          }
        })
        .toSet();

    int exitCode;
    try {
      // Get the Future representing the build and then block until everything is built.
      ListenableFuture<List<BuildRuleSuccess>> buildFuture = build.executeBuild(rulesToBuild);
      try {
        buildFuture.get();
      } catch (InterruptedException e) {
        try {
          buildFuture.cancel(true);
        } catch (CancellationException ignored) {
          // Rethrow original InterruptedException instead.
        }
        Thread.currentThread().interrupt();
        throw e;
      }
      exitCode = 0;
    } catch (IOException e) {
      console.printBuildFailureWithoutStacktrace(e);
      exitCode = 1;
    } catch (StepFailedException e) {
      console.printBuildFailureWithoutStacktrace(e);
      exitCode = e.getExitCode();
    } catch (ExecutionException e) {
      // This is likely a checked exception that was caught while building a build rule.
      Throwable cause = e.getCause();
      if (cause instanceof HumanReadableException) {
        throw ((HumanReadableException) cause);
      } else if (cause instanceof ExceptionWithHumanReadableMessage) {
        throw new HumanReadableException((ExceptionWithHumanReadableMessage) cause);
      } else {
        if (cause instanceof RuntimeException) {
          console.printBuildFailureWithStacktrace(e);
        } else {
          console.printBuildFailureWithoutStacktrace(e);
        }
        exitCode = 1;
      }
    }

    return exitCode;
  }

  Build getBuild() {
    Preconditions.checkNotNull(build);
    return build;
  }

  ImmutableList<BuildTarget> getBuildTargets() {
    return ImmutableList.copyOf(buildTargets);
  }

  @Override
  String getUsageIntro() {
    return "Specify one build rule to build.";
  }
}
