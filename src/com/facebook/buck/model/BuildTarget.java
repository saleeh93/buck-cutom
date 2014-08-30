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

package com.facebook.buck.model;

import com.facebook.buck.util.BuckConstant;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE)
public final class BuildTarget implements Comparable<BuildTarget>, HasBuildTarget {

  public static final String BUILD_TARGET_PREFIX = "//";

  private static final Pattern VALID_FLAVOR_PATTERN = Pattern.compile("[-a-zA-Z0-9_]+");

  private final Optional<String> repository;
  private final String baseName;
  private final String shortName;
  private final Optional<Flavor> flavor;
  private final String fullyQualifiedName;

  private BuildTarget(
      Optional<String> repository,
      String baseName,
      String shortName,
      Optional<Flavor> flavor) {
    Preconditions.checkNotNull(repository);
    Preconditions.checkNotNull(baseName);
    // shortName may be the empty string when parsing visibility patterns.
    Preconditions.checkNotNull(shortName);
    Preconditions.checkNotNull(flavor);

    Preconditions.checkArgument(baseName.startsWith(BUILD_TARGET_PREFIX),
        "baseName must start with %s but was %s",
        BUILD_TARGET_PREFIX,
        baseName);

    // There's a chance that the (String, String) constructor was called, but a flavour was
    // specified as part of the short name. Handle that case.
    int hashIndex = shortName.lastIndexOf("#");
    if (hashIndex != -1 && !flavor.isPresent()) {
      flavor = Optional.of(new Flavor(shortName.substring(hashIndex + 1)));
      shortName = shortName.substring(0, hashIndex);
    }

    Preconditions.checkArgument(!shortName.contains("#"),
        "Build target name cannot contain '#' but was: %s.",
        shortName);
    if (flavor.isPresent()) {
      Flavor flavorName = flavor.get();
      if (!Flavor.DEFAULT.equals(flavorName) &&
          !VALID_FLAVOR_PATTERN.matcher(flavorName.toString()).matches()) {
        throw new IllegalArgumentException("Invalid flavor: " + flavorName);
      }
    }

    this.repository = repository;
    // On Windows, baseName may contain backslashes, which are not permitted by BuildTarget.
    this.baseName = baseName.replace("\\", "/");
    this.shortName = shortName;
    this.flavor = flavor;
    this.fullyQualifiedName =
        (repository.isPresent() ? "@" + repository.get() : "") +
        baseName + ":" + shortName + getFlavorPostfix();
  }

  public Path getBuildFilePath() {
    return Paths.get(getBasePathWithSlash() + BuckConstant.BUILD_RULES_FILE_NAME);
  }

  @JsonProperty("repository")
  public Optional<String> getRepository() {
    return repository;
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "guava-latest". Note that the flavor of the target is included here.
   */
  public String getShortName() {
    return shortName + getFlavorPostfix();
  }

  public String getFlavorPostfix() {
    return (flavor.isPresent() && !flavor.get().equals(Flavor.DEFAULT) ? "#" + flavor.get() : "");
  }

  @JsonProperty("shortName")
  public String getShortNameOnly() {
    return shortName;
  }

  @JsonProperty("flavor")
  public Flavor getFlavor() {
    return flavor.or(Flavor.DEFAULT);
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava".
   */
  @JsonProperty("baseName")
  public String getBaseName() {
    return baseName;
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava/".
   */
  public String getBaseNameWithSlash() {
    return getBaseNameWithSlash(baseName);
  }

  /**
   * Helper function for getting BuildTarget base names with a trailing slash if needed.
   *
   * If baseName were //third_party/java/guava, then this would return  "//third_party/java/guava/".
   * If it were //, it would return //.
   */
  @Nullable
  public static String getBaseNameWithSlash(@Nullable String baseName) {
    return baseName == null || baseName.equals(BUILD_TARGET_PREFIX) ? baseName : baseName + "/";
  }

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "third_party/java/guava". This does not contain the "//" prefix so that it can be appended to
   * a file path.
   */
  public Path getBasePath() {
    return Paths.get(baseName.substring(BUILD_TARGET_PREFIX.length()));
  }

  /**
   * @return the value of {@link #getBasePath()} with a trailing slash, unless
   *     {@link #getBasePath()} returns the empty string, in which case this also returns the empty
   *     string
   */
  public String getBasePathWithSlash() {
    String basePath = getBasePath().toString();
    return basePath.isEmpty() ? "" : basePath + "/";
  }

  /**
   * If this build target is //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  public String getFullyQualifiedName() {
    return fullyQualifiedName;
  }

  @JsonIgnore
  public boolean isFlavored() {
    return flavor.isPresent();
  }

  @JsonIgnore
  public boolean isInProjectRoot() {
    return BUILD_TARGET_PREFIX.equals(baseName);
  }

  /**
   * @return a {@link BuildTarget} that is equal to the current one, but without the flavor. If
   *     this build target does not have a flavor, then this object will be returned.
   */
  public BuildTarget getUnflavoredTarget() {
    if (!isFlavored()) {
      return this;
    } else {
      return new BuildTarget(repository, baseName, shortName, Optional.<Flavor>absent());
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BuildTarget)) {
      return false;
    }
    BuildTarget that = (BuildTarget) o;
    return this.fullyQualifiedName.equals(that.fullyQualifiedName);
  }

  @Override
  public int hashCode() {
    return fullyQualifiedName.hashCode();
  }

  /** @return {@link #getFullyQualifiedName()} */
  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  @Override
  public int compareTo(BuildTarget target) {
    Preconditions.checkNotNull(target);
    return getFullyQualifiedName().compareTo(target.getFullyQualifiedName());
  }

  public static Builder builder(String baseName, String shortName) {
    return new Builder(baseName, shortName);
  }

  public static Builder builder(BuildTarget buildTarget) {
    return new Builder(buildTarget);
  }

  @Override
  public BuildTarget getBuildTarget() {
    return this;
  }

  public static class Builder {
    private Optional<String> repository = Optional.absent();
    private String baseName;
    private String shortName;
    private Optional<Flavor> flavor = Optional.absent();

    private Builder(String baseName, String shortName) {
      this.baseName = baseName;
      this.shortName = shortName;
    }

    private Builder(BuildTarget buildTarget) {
      this.repository = buildTarget.repository;
      this.baseName = buildTarget.baseName;
      this.shortName = buildTarget.shortName;
      this.flavor = buildTarget.flavor;
    }

    /**
     * Build targets are hashable and equality-comparable, so targets referring to the same
     * repository <strong>must</strong> use the same name. But build target syntax in BUCK files
     * does <strong>not</strong> have this property -- repository names are configured in the local
     * .buckconfig, and one project could use different naming from another. (And of course, targets
     * within an external project will need a @repo name prepended to them, to distinguish them from
     * targets in the root project.) It's the caller's responsibility to guarantee that repository
     * names are disambiguated before BuildTargets are created.
     */
    public Builder setRepository(String repo) {
      this.repository = Optional.of(repo);
      return this;
    }

    public Builder setFlavor(Flavor flavor) {
      this.flavor = Optional.of(flavor);
      return this;
    }

    @VisibleForTesting
    public Builder setFlavor(String flavor) {
      this.flavor = Optional.of(new Flavor(flavor));
      return this;
    }

    public BuildTarget build() {
      return new BuildTarget(repository, baseName, shortName, flavor);
    }
  }
}
