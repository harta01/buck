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

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.aapt.MiniAapt;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * An object that represents the resources of an android library.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUCK</code>:
 * <pre>
 * android_resources(
 *   name = 'res',
 *   res = 'res',
 *   assets = 'buck-assets',
 *   deps = [
 *     '//first-party/orca/lib-ui:lib-ui',
 *   ],
 * )
 * </pre>
 */
public class AndroidResource extends AbstractBuildRule
    implements
    AndroidPackageable,
    HasAndroidResourceDeps,
    InitializableFromDisk<AndroidResource.BuildOutput>,
    SupportsInputBasedRuleKey {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  @VisibleForTesting
  static final String METADATA_KEY_FOR_ABI = "ANDROID_RESOURCE_ABI_KEY";

  @VisibleForTesting
  static final String METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE = "METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE";

  @Nullable
  private final SourcePath res;

  /** contents of {@code res} under version control (i.e., not generated by another rule). */
  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSortedSet<? extends  SourcePath> resSrcs;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final Optional<SourcePath> additionalResKey;

  @Nullable
  private final SourcePath assets;

  /** contents of {@code assets} under version control (i.e., not generated by another rule). */
  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ImmutableSortedSet<? extends SourcePath> assetsSrcs;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final Optional<SourcePath> additionalAssetsKey;

  @Nullable
  private final Path pathToTextSymbolsDir;

  @Nullable
  private final Path pathToTextSymbolsFile;

  @Nullable
  private final Path pathToRDotJavaPackageFile;

  @AddToRuleKey
  @Nullable
  private final SourcePath manifestFile;

  @AddToRuleKey
  private final Supplier<ImmutableSortedSet<? extends  SourcePath>> symbolsOfDeps;

  @AddToRuleKey
  private final boolean hasWhitelistedStrings;

  @AddToRuleKey
  private final boolean resourceUnion;

  private final ImmutableSortedSet<BuildRule> deps;

  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  /**
   * This is the original {@code package} argument passed to this rule.
   */
  @AddToRuleKey
  @Nullable
  private final String rDotJavaPackageArgument;

  /**
   * Supplier that returns the package for the Java class generated for the resources in
   * {@link #res}, if any. The value for this supplier is determined, as follows:
   * <ul>
   *   <li>If the user specified a {@code package} argument, the supplier will return that value.
   *   <li>Failing that, when the rule is built, it will parse the package from the file specified
   *       by the {@code manifest} so that it can be returned by this supplier. (Note this also
   *       needs to work correctly if the rule is initialized from disk.)
   *   <li>In all other cases (e.g., both {@code package} and {@code manifest} are unspecified), the
   *       behavior is undefined.
   * </ul>
   */
  private final Supplier<String> rDotJavaPackageSupplier;

  private final AtomicReference<String> rDotJavaPackage;

  public AndroidResource(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      final ImmutableSortedSet<BuildRule> deps,
      @Nullable final SourcePath res,
      ImmutableSortedSet<? extends SourcePath> resSrcs,
      Optional<SourcePath> additionalResKey,
      @Nullable String rDotJavaPackageArgument,
      @Nullable SourcePath assets,
      ImmutableSortedSet<? extends SourcePath> assetsSrcs,
      Optional<SourcePath> additionalAssetsKey,
      @Nullable SourcePath manifestFile,
      Supplier<ImmutableSortedSet<? extends SourcePath>> symbolFilesFromDeps,
      boolean hasWhitelistedStrings,
      boolean resourceUnion) {
    super(
        buildRuleParams.appendExtraDeps(
            Suppliers.compose(resolver::filterBuildRuleInputs, symbolFilesFromDeps)),
        resolver);
    if (res != null && rDotJavaPackageArgument == null && manifestFile == null) {
      throw new HumanReadableException(
          "When the 'res' is specified for android_resource() %s, at least one of 'package' or " +
              "'manifest' must be specified.",
          getBuildTarget());
    }

    this.res = res;
    this.resSrcs = resSrcs;
    this.additionalResKey = additionalResKey;
    this.assets = assets;
    this.assetsSrcs = assetsSrcs;
    this.additionalAssetsKey = additionalAssetsKey;
    this.manifestFile = manifestFile;
    this.symbolsOfDeps = symbolFilesFromDeps;
    this.hasWhitelistedStrings = hasWhitelistedStrings;
    this.resourceUnion = resourceUnion;

    BuildTarget buildTarget = buildRuleParams.getBuildTarget();
    if (res == null) {
      pathToTextSymbolsDir = null;
      pathToTextSymbolsFile = null;
      pathToRDotJavaPackageFile = null;
    } else {
      pathToTextSymbolsDir =
          BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "__%s_text_symbols__");
      pathToTextSymbolsFile = pathToTextSymbolsDir.resolve("R.txt");
      pathToRDotJavaPackageFile = pathToTextSymbolsDir.resolve("RDotJavaPackage.txt");
    }

    this.deps = deps;

    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);

    this.rDotJavaPackageArgument = rDotJavaPackageArgument;
    this.rDotJavaPackage = new AtomicReference<>();
    if (rDotJavaPackageArgument != null) {
      this.rDotJavaPackage.set(rDotJavaPackageArgument);
    }

    this.rDotJavaPackageSupplier = () -> {
      String rDotJavaPackage1 = AndroidResource.this.rDotJavaPackage.get();
      if (rDotJavaPackage1 != null) {
        return rDotJavaPackage1;
      } else {
        throw new RuntimeException(
            "rDotJavaPackage for " + AndroidResource.this.getBuildTarget().toString() +
            " was requested before it was made available.");
      }
    };
  }

  public AndroidResource(
      final BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      final ImmutableSortedSet<BuildRule> deps,
      @Nullable final SourcePath res,
      ImmutableSortedSet<? extends SourcePath> resSrcs,
      Optional<SourcePath> additionalResKey,
      @Nullable String rDotJavaPackageArgument,
      @Nullable SourcePath assets,
      ImmutableSortedSet<? extends SourcePath> assetsSrcs,
      Optional<SourcePath> additionalAssetsKey,
      @Nullable SourcePath manifestFile,
      boolean hasWhitelistedStrings) {
    this(
        buildRuleParams,
        resolver,
        deps,
        res,
        resSrcs,
        additionalResKey,
        rDotJavaPackageArgument,
        assets,
        assetsSrcs,
        additionalAssetsKey,
        manifestFile,
        hasWhitelistedStrings,
        false);
  }

  public AndroidResource(
      final BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      final ImmutableSortedSet<BuildRule> deps,
      @Nullable final SourcePath res,
      ImmutableSortedSet<? extends SourcePath> resSrcs,
      Optional<SourcePath> additionalResKey,
      @Nullable String rDotJavaPackageArgument,
      @Nullable SourcePath assets,
      ImmutableSortedSet<? extends SourcePath> assetsSrcs,
      Optional<SourcePath> additionalAssetsKey,
      @Nullable SourcePath manifestFile,
      boolean hasWhitelistedStrings,
      boolean resourceUnion) {
    this(
        buildRuleParams,
        resolver,
        deps,
        res,
        resSrcs,
        additionalResKey,
        rDotJavaPackageArgument,
        assets,
        assetsSrcs,
        additionalAssetsKey,
        manifestFile,
        () -> FluentIterable.from(buildRuleParams.getDeps())
            .filter(HasAndroidResourceDeps.class)
            .filter(NON_EMPTY_RESOURCE)
            .transform(GET_RES_SYMBOLS_TXT)
            .toSortedSet(Ordering.natural()),
        hasWhitelistedStrings,
        resourceUnion);
  }

  @Override
  @Nullable
  public SourcePath getRes() {
    return res;
  }

  @Override
  @Nullable
  public SourcePath getAssets() {
    return assets;
  }

  @Nullable
  public SourcePath getManifestFile() {
    return manifestFile;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    // If there is no res directory, then there is no R.java to generate, so the ABI key doesn't
    // need to take anything into account.
    // TODO(bolinfest): Change android_resources() so that 'res' is required.
    if (getRes() == null) {
      buildableContext.addMetadata(
          METADATA_KEY_FOR_ABI,
          Hashing.sha1().newHasher().hash().toString());
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        new MakeCleanDirectoryStep(
            getProjectFilesystem(),
            Preconditions.checkNotNull(pathToTextSymbolsDir)));

    // If the 'package' was not specified for this android_resource(), then attempt to parse it
    // from the AndroidManifest.xml.
    if (rDotJavaPackageArgument == null) {
      Preconditions.checkNotNull(
          manifestFile,
          "manifestFile cannot be null when res is non-null and rDotJavaPackageArgument is " +
              "null. This should already be enforced by the constructor.");
      steps.add(
          new ExtractFromAndroidManifestStep(
              getResolver().getAbsolutePath(manifestFile),
              getProjectFilesystem(),
              buildableContext,
              METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE,
              Preconditions.checkNotNull(pathToRDotJavaPackageFile)));
      buildableContext.recordArtifact(Preconditions.checkNotNull(pathToRDotJavaPackageFile));
    }

    ImmutableSet<Path> pathsToSymbolsOfDeps = symbolsOfDeps.get().stream()
        .map(getResolver()::getAbsolutePath)
        .collect(MoreCollectors.toImmutableSet());

    steps.add(
        new MiniAapt(
            getResolver(),
            getProjectFilesystem(),
            Preconditions.checkNotNull(res),
            Preconditions.checkNotNull(pathToTextSymbolsFile),
            pathsToSymbolsOfDeps,
            resourceUnion));

    buildableContext.recordArtifact(Preconditions.checkNotNull(pathToTextSymbolsFile));

    steps.add(
        new RecordFileSha1Step(
            getProjectFilesystem(),
            Preconditions.checkNotNull(pathToTextSymbolsFile),
            METADATA_KEY_FOR_ABI,
            buildableContext));

    return steps.build();
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    return pathToTextSymbolsDir;
  }

  @Override
  @Nullable
  public SourcePath getPathToTextSymbolsFile() {
    return new BuildTargetSourcePath(getBuildTarget(), pathToTextSymbolsFile);
  }

  @Override
  public Sha1HashCode getTextSymbolsAbiKey() {
    return buildOutputInitializer.getBuildOutput().textSymbolsAbiKey;
  }

  @Override
  public String getRDotJavaPackage() {
    String rDotJavaPackage = rDotJavaPackageSupplier.get();
    if (rDotJavaPackage == null) {
      throw new RuntimeException("No package for " + getBuildTarget());
    }
    return rDotJavaPackage;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<Sha1HashCode> sha1HashCode = onDiskBuildInfo.getHash(METADATA_KEY_FOR_ABI);
    Preconditions.checkState(
        sha1HashCode.isPresent(),
        "OnDiskBuildInfo should have a METADATA_KEY_FOR_ABI hash");
    Optional<String> rDotJavaPackageFromAndroidManifest = onDiskBuildInfo.getValue(
        METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE);
    if (rDotJavaPackageFromAndroidManifest.isPresent()) {
      rDotJavaPackage.set(rDotJavaPackageFromAndroidManifest.get());
    }
    return new BuildOutput(sha1HashCode.get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(deps);
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (res != null) {
      if (hasWhitelistedStrings) {
        collector.addStringWhitelistedResourceDirectory(getBuildTarget(), res);
      } else {
        collector.addResourceDirectory(getBuildTarget(), res);
      }
    }
    if (assets != null) {
      collector.addAssetsDirectory(getBuildTarget(), assets);
    }
  }

  public static class BuildOutput {
    private final Sha1HashCode textSymbolsAbiKey;

    public BuildOutput(Sha1HashCode textSymbolsAbiKey) {
      this.textSymbolsAbiKey = textSymbolsAbiKey;
    }
  }
}
