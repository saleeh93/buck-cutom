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

package com.facebook.buck.apple.xcode;

import com.facebook.buck.apple.SchemeActionType;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Collects target references and generates an xcscheme.
 *
 * To register entries in the scheme, clients must add:
 * <ul>
 * <li>associations between buck rules and Xcode targets</li>
 * <li>associations between Xcode targets and the projects that contain them</li>
 * </ul>
 * <p>
 * Both of these values can be pulled out of {@link ProjectGenerator}.
 */
class SchemeGenerator {
  private static final Logger LOG = Logger.get(SchemeGenerator.class);

  private final ProjectFilesystem projectFilesystem;
  private final BuildRule primaryRule;
  private final ImmutableSet<BuildRule> orderedBuildRules;
  private final ImmutableSet<BuildRule> orderedTestBuildRules;
  private final ImmutableSet<BuildRule> orderedTestBundleRules;
  private final String schemeName;
  private final Path outputDirectory;
  private final ImmutableMap<SchemeActionType, String> actionConfigNames;
  private final ImmutableMap<BuildRule, PBXTarget> buildRuleToTargetMap;
  private final ImmutableMap<PBXTarget, Path> targetToProjectPathMap;

  public SchemeGenerator(
      ProjectFilesystem projectFilesystem,
      BuildRule primaryRule,
      Iterable<BuildRule> orderedBuildRules,
      Iterable<BuildRule> orderedTestBuildRules,
      Iterable<BuildRule> orderedTestBundleRules,
      String schemeName,
      Path outputDirectory,
      Map<SchemeActionType, String> actionConfigNames,
      Map<BuildRule, PBXTarget> buildRuleToTargetMap,
      Map<PBXTarget, Path> targetToProjectPathMap) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.primaryRule = Preconditions.checkNotNull(primaryRule);
    this.orderedBuildRules = ImmutableSet.copyOf(orderedBuildRules);
    this.orderedTestBuildRules = ImmutableSet.copyOf(orderedTestBuildRules);
    this.orderedTestBundleRules = ImmutableSet.copyOf(orderedTestBundleRules);
    this.schemeName = Preconditions.checkNotNull(schemeName);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    this.actionConfigNames = ImmutableMap.copyOf(actionConfigNames);
    this.buildRuleToTargetMap = ImmutableMap.copyOf(buildRuleToTargetMap);
    this.targetToProjectPathMap = ImmutableMap.copyOf(targetToProjectPathMap);

    LOG.debug(
        "Generating scheme with build rules %s, test build rules %s, test bundle rules %s",
        orderedBuildRules,
        orderedTestBuildRules,
        orderedTestBundleRules);

    for (BuildRule rule : orderedBuildRules) {
      expectTargetMapContainsRule(rule);
    }

    for (BuildRule rule : orderedTestBuildRules) {
      expectTargetMapContainsRule(rule);
    }

    for (BuildRule rule : orderedTestBundleRules) {
      expectTargetMapContainsRule(rule);
    }
  }

  private void expectTargetMapContainsRule(BuildRule rule) {
    if (!buildRuleToTargetMap.containsKey(rule)) {
      throw new HumanReadableException(
          "Scheme generation failed: No project containing required target %s was found.",
          rule.getFullyQualifiedName());
    }
  }

  public Path writeScheme() throws IOException {
    Map<BuildRule, XCScheme.BuildableReference>
        buildRuleToBuildableReferenceMap = Maps.newHashMap();

    for (BuildRule rule : Iterables.concat(orderedBuildRules, orderedTestBuildRules)) {
      PBXTarget target = buildRuleToTargetMap.get(rule);

      String blueprintName = target.getProductName();
      if (blueprintName == null) {
        blueprintName = target.getName();
      }
      XCScheme.BuildableReference buildableReference = new XCScheme.BuildableReference(
          outputDirectory.getParent().relativize(
              targetToProjectPathMap.get(target)
          ).toString(),
          target.getGlobalID(),
          target.getProductReference().getName(),
          blueprintName);
      buildRuleToBuildableReferenceMap.put(rule, buildableReference);
    }

    XCScheme.BuildAction buildAction = new XCScheme.BuildAction();

    // For aesthetic reasons put all non-test build actions before all test build actions.
    for (BuildRule rule : orderedBuildRules) {
      addBuildActionForRule(
          buildRuleToBuildableReferenceMap.get(rule),
          XCScheme.BuildActionEntry.BuildFor.DEFAULT,
          buildAction);
    }

    for (BuildRule rule : orderedTestBuildRules) {
      addBuildActionForRule(
          buildRuleToBuildableReferenceMap.get(rule),
          XCScheme.BuildActionEntry.BuildFor.TEST_ONLY,
          buildAction);
    }

    XCScheme.TestAction testAction = new XCScheme.TestAction(
        actionConfigNames.get(SchemeActionType.TEST));
    for (BuildRule rule : orderedTestBundleRules) {
      XCScheme.BuildableReference buildableReference = buildRuleToBuildableReferenceMap.get(rule);
      XCScheme.TestableReference testableReference =
          new XCScheme.TestableReference(buildableReference);
      testAction.addTestableReference(testableReference);
    }

    Optional<XCScheme.LaunchAction> launchAction = Optional.absent();
    Optional<XCScheme.ProfileAction> profileAction = Optional.absent();

    XCScheme.BuildableReference primaryBuildableReference =
        buildRuleToBuildableReferenceMap.get(primaryRule);
    if (primaryBuildableReference != null) {
      launchAction = Optional.of(new XCScheme.LaunchAction(
          primaryBuildableReference,
          actionConfigNames.get(SchemeActionType.LAUNCH)));
      profileAction = Optional.of(new XCScheme.ProfileAction(
          primaryBuildableReference,
          actionConfigNames.get(SchemeActionType.PROFILE)));
    }
    XCScheme.AnalyzeAction analyzeAction = new XCScheme.AnalyzeAction(
        actionConfigNames.get(SchemeActionType.ANALYZE));
    XCScheme.ArchiveAction archiveAction = new XCScheme.ArchiveAction(
        actionConfigNames.get(SchemeActionType.ARCHIVE));

    XCScheme scheme = new XCScheme(
        schemeName,
        Optional.of(buildAction),
        Optional.of(testAction),
        launchAction,
        profileAction,
        Optional.of(analyzeAction),
        Optional.of(archiveAction));

    Path schemeDirectory = outputDirectory.resolve("xcshareddata/xcschemes");
    projectFilesystem.mkdirs(schemeDirectory);
    Path schemePath = schemeDirectory.resolve(schemeName + ".xcscheme");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      serializeScheme(scheme, outputStream);
      String contentsToWrite = outputStream.toString();
      if (MorePaths.fileContentsDiffer(
          new ByteArrayInputStream(contentsToWrite.getBytes(Charsets.UTF_8)),
          schemePath,
          projectFilesystem)) {
        projectFilesystem.writeContentsToPath(outputStream.toString(), schemePath);
      }
    }
    return schemePath;
  }

  private static void addBuildActionForRule(
      XCScheme.BuildableReference buildableReference,
      EnumSet<XCScheme.BuildActionEntry.BuildFor> buildFor,
      XCScheme.BuildAction buildAction) {
      XCScheme.BuildActionEntry entry = new XCScheme.BuildActionEntry(
          buildableReference,
          buildFor);
      buildAction.addBuildAction(entry);
    }


  public static Element serializeBuildableReference(
      Document doc,
      XCScheme.BuildableReference buildableReference) {
    Element refElem = doc.createElement("BuildableReference");
    refElem.setAttribute("BuildableIdentifier", "primary");
    refElem.setAttribute("BlueprintIdentifier", buildableReference.getBlueprintIdentifier());
    refElem.setAttribute("BuildableName", buildableReference.getBuildableName());
    refElem.setAttribute("BlueprintName", buildableReference.getBlueprintName());
    String referencedContainer = "container:" + buildableReference.getContainerRelativePath();
    refElem.setAttribute("ReferencedContainer", referencedContainer);
    return refElem;
  }

  public static Element serializeBuildAction(Document doc, XCScheme.BuildAction buildAction) {
    Element buildActionElem = doc.createElement("BuildAction");
    buildActionElem.setAttribute("parallelizeBuildables", "NO");
    buildActionElem.setAttribute("buildImplicitDependencies", "NO");

    Element buildActionEntriesElem = doc.createElement("BuildActionEntries");
    buildActionElem.appendChild(buildActionEntriesElem);

    for (XCScheme.BuildActionEntry entry : buildAction.getBuildActionEntries()) {
      Element entryElem = doc.createElement("BuildActionEntry");
      buildActionEntriesElem.appendChild(entryElem);

      EnumSet<XCScheme.BuildActionEntry.BuildFor> buildFor = entry.getBuildFor();
      boolean buildForRunning = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.RUNNING);
      entryElem.setAttribute("buildForRunning", buildForRunning ? "YES" : "NO");
      boolean buildForTesting = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.TESTING);
      entryElem.setAttribute("buildForTesting", buildForTesting ? "YES" : "NO");
      boolean buildForProfiling = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.PROFILING);
      entryElem.setAttribute("buildForProfiling", buildForProfiling ? "YES" : "NO");
      boolean buildForArchiving = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.ARCHIVING);
      entryElem.setAttribute("buildForArchiving", buildForArchiving ? "YES" : "NO");
      boolean buildForAnalyzing = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.ANALYZING);
      entryElem.setAttribute("buildForAnalyzing", buildForAnalyzing ? "YES" : "NO");

      Element refElem = serializeBuildableReference(doc, entry.getBuildableReference());
      entryElem.appendChild(refElem);
    }

    return buildActionElem;
  }

  public static Element serializeTestAction(Document doc, XCScheme.TestAction testAction) {
    Element testActionElem = doc.createElement("TestAction");
    testActionElem.setAttribute("shouldUseLaunchSchemeArgsEnv", "YES");

    Element testablesElem = doc.createElement("Testables");
    testActionElem.appendChild(testablesElem);

    for (XCScheme.TestableReference testable : testAction.getTestables()) {
      Element testableElem = doc.createElement("TestableReference");
      testablesElem.appendChild(testableElem);
      testableElem.setAttribute("skipped", "NO");

      Element refElem = serializeBuildableReference(doc, testable.getBuildableReference());
      testableElem.appendChild(refElem);
    }

    return testActionElem;
  }

  public static Element serializeLaunchAction(Document doc, XCScheme.LaunchAction launchAction) {
    Element launchActionElem = doc.createElement("LaunchAction");

    Element productRunnableElem = doc.createElement("BuildableProductRunnable");
    launchActionElem.appendChild(productRunnableElem);

    Element refElem = serializeBuildableReference(doc, launchAction.getBuildableReference());
    productRunnableElem.appendChild(refElem);

    return launchActionElem;
  }

  public static Element serializeProfileAction(Document doc, XCScheme.ProfileAction profileAction) {
    Element profileActionElem = doc.createElement("ProfileAction");

    Element productRunnableElem = doc.createElement("BuildableProductRunnable");
    profileActionElem.appendChild(productRunnableElem);

    Element refElem = serializeBuildableReference(doc, profileAction.getBuildableReference());
    productRunnableElem.appendChild(refElem);

    return profileActionElem;
  }

  private static void serializeScheme(XCScheme scheme, OutputStream stream) {
    DocumentBuilder docBuilder;
    Transformer transformer;
    try {
      docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      transformer = TransformerFactory.newInstance().newTransformer();
    } catch (ParserConfigurationException | TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }

    DOMImplementation domImplementation = docBuilder.getDOMImplementation();
    Document doc = domImplementation.createDocument(null, "Scheme", null);
    doc.setXmlVersion("1.0");

    Element rootElem = doc.getDocumentElement();
    rootElem.setAttribute("LastUpgradeVersion", "0500");
    rootElem.setAttribute("version", "1.7");

    Optional<XCScheme.BuildAction> buildAction = scheme.getBuildAction();
    if (buildAction.isPresent()) {
      Element buildActionElem = serializeBuildAction(doc, buildAction.get());
      rootElem.appendChild(buildActionElem);
    }

    Optional<XCScheme.TestAction> testAction = scheme.getTestAction();
    if (testAction.isPresent()) {
      Element testActionElem = serializeTestAction(doc, testAction.get());
      testActionElem.setAttribute(
          "buildConfiguration", scheme.getTestAction().get().getBuildConfiguration());
      rootElem.appendChild(testActionElem);
    }

    Optional<XCScheme.LaunchAction> launchAction = scheme.getLaunchAction();
    if (launchAction.isPresent()) {
      Element launchActionElem = serializeLaunchAction(doc, launchAction.get());
      launchActionElem.setAttribute(
          "buildConfiguration", launchAction.get().getBuildConfiguration());
      rootElem.appendChild(launchActionElem);
    }

    Optional<XCScheme.ProfileAction> profileAction = scheme.getProfileAction();
    if (profileAction.isPresent()) {
      Element profileActionElem = serializeProfileAction(doc, profileAction.get());
      profileActionElem.setAttribute(
          "buildConfiguration", profileAction.get().getBuildConfiguration());
      rootElem.appendChild(profileActionElem);
    }

    Optional<XCScheme.AnalyzeAction> analyzeAction = scheme.getAnalyzeAction();
    if (analyzeAction.isPresent()) {
      Element analyzeActionElem = doc.createElement("AnalyzeAction");
      analyzeActionElem.setAttribute(
          "buildConfiguration", analyzeAction.get().getBuildConfiguration());
      rootElem.appendChild(analyzeActionElem);
    }

    Optional<XCScheme.ArchiveAction> archiveAction = scheme.getArchiveAction();
    if (archiveAction.isPresent()) {
      Element archiveActionElem = doc.createElement("ArchiveAction");
      archiveActionElem.setAttribute(
          "buildConfiguration", archiveAction.get().getBuildConfiguration());
      archiveActionElem.setAttribute("revealArchiveInOrganizer", "YES");
      rootElem.appendChild(archiveActionElem);
    }

    // write out

    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(stream);

    try {
      transformer.transform(source, result);
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
  }
}
