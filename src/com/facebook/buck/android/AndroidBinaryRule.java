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
import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.android.common.SdkConstants;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.java.AccumulateClassNames;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DefaultBuildRuleBuilderParams;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.InstallableBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.shell.EchoStep;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipDirectoryWithMaxDeflateStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * <pre>
 * android_binary(
 *   name = 'messenger',
 *   manifest = 'AndroidManifest.xml',
 *   target = 'Google Inc.:Google APIs:16',
 *   deps = [
 *     '//src/com/facebook/messenger:messenger_library',
 *   ],
 * )
 * </pre>
 */
public class AndroidBinaryRule extends DoNotUseAbstractBuildable implements
    HasAndroidPlatformTarget, HasClasspathEntries, InstallableBuildRule {

  private final static BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);

  /**
   * The largest file size Froyo will deflate.
   */
  private final long FROYO_DEFLATE_LIMIT_BYTES = 1 << 20;

  /**
   * This list of package types is taken from the set of targets that the default build.xml provides
   * for Android projects.
   * <p>
   * Note: not all package types are supported. If unsupported, will be treated as "DEBUG".
   */
  static enum PackageType {
    DEBUG,
    INSTRUMENTED,
    RELEASE,
    TEST,
    ;

    /**
     * @return true if ProGuard should be used to obfuscate the output
     */
    private final boolean isBuildWithObfuscation() {
      return this == RELEASE;
    }

    private final boolean isCrunchPngFiles() {
      return this == RELEASE;
    }
  }

  static enum TargetCpuType {
    ARM,
    ARMV7,
    X86,
    MIPS,
  }

  static enum ResourceCompressionMode {
    DISABLED(/* isCompressResources */ false, /* isStoreStringsAsAssets */ false),
    ENABLED(/* isCompressResources */ true, /* isStoreStringsAsAssets */ false),
    ENABLED_WITH_STRINGS_AS_ASSETS(
      /* isCompressResources */ true,
      /* isStoreStringsAsAssets */ true),
    ;

    private final boolean isCompressResources;
    private final boolean isStoreStringsAsAssets;

    private ResourceCompressionMode(boolean isCompressResources, boolean isStoreStringsAsAssets) {
      this.isCompressResources = isCompressResources;
      this.isStoreStringsAsAssets = isStoreStringsAsAssets;
    }

    public boolean isCompressResources() {
      return isCompressResources;
    }

    public boolean isStoreStringsAsAssets() {
      return isStoreStringsAsAssets;
    }
  }

  private final Path manifest;
  private final String target;
  private final ImmutableSortedSet<BuildRule> classpathDeps;
  private final Keystore keystore;
  private final PackageType packageType;
  private final ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex;
  private DexSplitMode dexSplitMode;
  private final boolean useAndroidProguardConfigWithOptimizations;
  private final Optional<SourcePath> proguardConfig;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<String> primaryDexSubstrings;
  private final long linearAllocHardLimit;

  /**
   * File that whitelists the class files that should be in the primary dex.
   * <p>
   * Values in this file must match JAR entries exactly, so they should contain path separators.
   * For example:
   * <pre>
   * com/google/common/collect/ImmutableSet.class
   * </pre>
   */
  private final Optional<SourcePath> primaryDexClassesFile;

  private final FilterResourcesStep.ResourceFilter resourceFilter;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final ImmutableSet<DexProducedFromJavaLibraryThatContainsClassFiles> preDexDeps;
  private final ImmutableSortedSet<BuildRule> preprocessJavaClassesDeps;
  private final Optional<String> preprocessJavaClassesBash;
  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;

  /** This path is guaranteed to end with a slash. */
  private final String outputGenDirectory;

  /**
   * @param target the Android platform version to target, e.g., "Google Inc.:Google APIs:16". You
   *     can find the list of valid values on your system by running
   *     {@code android list targets --compact}.
   */
  protected AndroidBinaryRule(
      BuildRuleParams buildRuleParams,
      Path manifest,
      String target,
      ImmutableSortedSet<BuildRule> classpathDeps,
      Keystore keystore,
      PackageType packageType,
      Set<BuildRule> buildRulesToExcludeFromDex,
      DexSplitMode dexSplitMode,
      boolean useAndroidProguardConfigWithOptimizations,
      Optional<SourcePath> proguardConfig,
      ResourceCompressionMode resourceCompressionMode,
      Set<String> primaryDexSubstrings,
      long linearAllocHardLimit,
      Optional<SourcePath> primaryDexClassesFile,
      FilterResourcesStep.ResourceFilter resourceFilter,
      Set<TargetCpuType> cpuFilters,
      Set<DexProducedFromJavaLibraryThatContainsClassFiles> preDexDeps,
      Set<BuildRule> preprocessJavaClassesDeps,
      Optional<String> preprocessJavaClassesBash) {
    super(buildRuleParams);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.target = Preconditions.checkNotNull(target);
    this.classpathDeps = ImmutableSortedSet.copyOf(classpathDeps);
    this.keystore = Preconditions.checkNotNull(keystore);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.buildRulesToExcludeFromDex = ImmutableSortedSet.copyOf(buildRulesToExcludeFromDex);
    this.dexSplitMode = Preconditions.checkNotNull(dexSplitMode);
    this.useAndroidProguardConfigWithOptimizations = useAndroidProguardConfigWithOptimizations;
    this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.primaryDexSubstrings = ImmutableSet.copyOf(primaryDexSubstrings);
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.primaryDexClassesFile = Preconditions.checkNotNull(primaryDexClassesFile);
    this.outputGenDirectory = String.format("%s/%s",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePathWithSlash());
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
    this.cpuFilters = ImmutableSet.copyOf(cpuFilters);
    this.preDexDeps = ImmutableSet.copyOf(preDexDeps);
    this.preprocessJavaClassesDeps = ImmutableSortedSet.copyOf(preprocessJavaClassesDeps);
    this.preprocessJavaClassesBash = Preconditions.checkNotNull(preprocessJavaClassesBash);
    this.transitiveDependencyGraph = new AndroidTransitiveDependencyGraph(this);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_BINARY;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public String getAndroidPlatformTarget() {
    return target;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    super.appendToRuleKey(builder)
        .setInput("manifest", manifest)
        .set("target", target)
        .set("keystore", keystore.getBuildTarget().getFullyQualifiedName())
        .setRuleNames("classpathDeps", classpathDeps)
        .set("packageType", packageType.toString())
        .set("buildRulesToExcludeFromDex", buildRulesToExcludeFromDex)
        .set("useAndroidProguardConfigWithOptimizations", useAndroidProguardConfigWithOptimizations)
        .set("proguardConfig", proguardConfig.transform(SourcePath.TO_REFERENCE))
        .set("resourceCompressionMode", resourceCompressionMode.toString())
        .set("primaryDexSubstrings", primaryDexSubstrings)
        .set("linearAllocHardLimit", linearAllocHardLimit)
        .set("primaryDexClassesFile", primaryDexClassesFile.transform(SourcePath.TO_REFERENCE))
        .set("resourceFilter", resourceFilter.getDescription())
        .set("cpuFilters", ImmutableSortedSet.copyOf(cpuFilters).toString())
        .set("preprocessJavaClassesBash", preprocessJavaClassesBash)
        .set("preprocessJavaClassesDeps", preprocessJavaClassesDeps);
    return dexSplitMode.appendToRuleKey("dexSplitMode", builder);
  }

  public ImmutableSortedSet<BuildRule> getBuildRulesToExcludeFromDex() {
    return buildRulesToExcludeFromDex;
  }

  public AndroidTransitiveDependencyGraph getTransitiveDependencyGraph() {
    return transitiveDependencyGraph;
  }

  public Optional<SourcePath> getProguardConfig() {
    return proguardConfig;
  }

  public boolean isRelease() {
    return packageType == PackageType.RELEASE;
  }

  private boolean isCompressResources(){
    return resourceCompressionMode.isCompressResources();
  }

  private boolean isStoreStringsAsAssets() {
    return resourceCompressionMode.isStoreStringsAsAssets();
  }

  public ResourceCompressionMode getResourceCompressionMode() {
    return resourceCompressionMode;
  }

  public boolean requiresResourceFilter() {
    return resourceFilter.isEnabled() || isStoreStringsAsAssets();
  }

  public FilterResourcesStep.ResourceFilter getResourceFilter() {
    return this.resourceFilter;
  }

  public ImmutableSet<TargetCpuType> getCpuFilters() {
    return this.cpuFilters;
  }

  public ImmutableSet<DexProducedFromJavaLibraryThatContainsClassFiles> getPreDexDeps() {
    return preDexDeps;
  }

  public ImmutableSortedSet<BuildRule> getPreprocessJavaClassesDeps() {
    return preprocessJavaClassesDeps;
  }

  public Optional<String> getPreprocessJavaClassesBash() {
    return preprocessJavaClassesBash;
  }

  /**
   * Native libraries compiled for different CPU architectures are placed in the
   * respective ABI subdirectories, such as 'armeabi', 'armeabi-v7a', 'x86' and 'mips'.
   * This looks at the cpu filter and returns the correct subdirectory. If cpu filter is
   * not present or not supported, returns Optional.absent();
   */
  private static Optional<String> getAbiDirectoryComponent(TargetCpuType cpuType) {
    String component = null;
    if (cpuType.equals(TargetCpuType.ARM)) {
      component = SdkConstants.ABI_ARMEABI;
    } else if (cpuType.equals(TargetCpuType.ARMV7)) {
      component = SdkConstants.ABI_ARMEABI_V7A;
    } else if (cpuType.equals(TargetCpuType.X86)) {
      component = SdkConstants.ABI_INTEL_ATOM;
    } else if (cpuType.equals(TargetCpuType.MIPS)) {
      component = SdkConstants.ABI_MIPS;
    }
    return Optional.fromNullable(component);

  }

  @VisibleForTesting
  void copyNativeLibrary(String sourceDir,
      String destinationDir,
      ImmutableList.Builder<Step> commands) {
    Path sourceDirPath = Paths.get(sourceDir);
    Path destinationDirPath = Paths.get(destinationDir);

    if (getCpuFilters().isEmpty()) {
      commands.add(new CopyStep(sourceDirPath, destinationDirPath, true));
    } else {
      for (TargetCpuType cpuType: getCpuFilters()) {
        Optional<String> abiDirectoryComponent = getAbiDirectoryComponent(cpuType);
        Preconditions.checkState(abiDirectoryComponent.isPresent());

        final Path libSourceDir = sourceDirPath.resolve(abiDirectoryComponent.get());
        Path libDestinationDir = destinationDirPath.resolve(abiDirectoryComponent.get());

        final MkdirStep mkDirStep = new MkdirStep(libDestinationDir);
        final CopyStep copyStep = new CopyStep(libSourceDir, libDestinationDir, true);
        commands.add(new Step() {
          @Override
          public int execute(ExecutionContext context) {
            if (!context.getProjectFilesystem().exists(libSourceDir.toString())) {
              return 0;
            }
            if (mkDirStep.execute(context) == 0 && copyStep.execute(context) == 0) {
              return 0;
            }
            return 1;
          }

          @Override
          public String getShortName() {
            return "copy_native_libraries";
          }

          @Override
          public String getDescription(ExecutionContext context) {
            ImmutableList.Builder<String> stringBuilder = ImmutableList.builder();
            stringBuilder.add(String.format("[ -d %s ]", libSourceDir.toString()));
            stringBuilder.add(mkDirStep.getDescription(context));
            stringBuilder.add(copyStep.getDescription(context));
            return Joiner.on(" && ").join(stringBuilder.build());
          }
        });
      }
    }
  }

  /** The APK at this path is the final one that points to an APK that a user should install. */
  @Override
  public Path getApkPath() {
    return MorePaths.newPathInstance(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".apk"));
  }

  @Override
  public String getPathToOutputFile() {
    return getApkPath().toString();
  }

  @Override
  public List<String> getInputsToCompareToOutput() {
    ImmutableList.Builder<String> inputs = ImmutableList.builder();
    inputs.add(manifest.toString());
    if (proguardConfig.isPresent()) {
      SourcePath sourcePath = proguardConfig.get();
      // Alternatively, if it is a BuildTargetSourcePath, then it should not be included.
      if (sourcePath instanceof FileSourcePath) {
        inputs.add(sourcePath.asReference());
      }
    }

    return inputs.build();
  }

  /**
   * Sets up filtering of resources, images/drawables and strings in particular, based on build
   * rule parameters {@link #resourceFilter} and {@link #isStoreStringsAsAssets}.
   *
   * {@link com.facebook.buck.android.FilterResourcesStep.ResourceFilter} {@code resourceFilter}
   * determines which drawables end up in the APK (based on density - mdpi, hdpi etc), and also
   * whether higher density drawables get scaled down to the specified density (if not present).
   *
   * {@code isStoreStringsAsAssets} determines whether non-english string resources are packaged
   * separately as assets (and not bundled together into the {@code resources.arsc} file).
   */
  @VisibleForTesting
  FilterResourcesStep getFilterResourcesStep(Set<String> resourceDirectories) {
    ImmutableBiMap.Builder<String, String> filteredResourcesDirMapBuilder = ImmutableBiMap.builder();
    Path resDestinationBasePath = getBinPath("__filtered__%s__");
    int count = 0;
    for (String resDir : resourceDirectories) {
      filteredResourcesDirMapBuilder.put(resDir,
          resDestinationBasePath.resolve(String.valueOf(count++)).toString());
    }

    ImmutableBiMap<String, String> resSourceToDestDirMap = filteredResourcesDirMapBuilder.build();
    FilterResourcesStep.Builder filterResourcesStepBuilder = FilterResourcesStep.builder()
        .setInResToOutResDirMap(resSourceToDestDirMap)
        .setResourceFilter(resourceFilter);

    if (isStoreStringsAsAssets()) {
      filterResourcesStepBuilder.enableStringsFilter();
    }

    return filterResourcesStepBuilder.build();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    // Map from asset name to pathname for extra files to be added to assets.
    ImmutableMap.Builder<String, File> extraAssetsBuilder = ImmutableMap.builder();

    // Symlink the manifest to a path named AndroidManifest.xml. Do this before running any other
    // commands to ensure that it is available at the desired path.
    commands.add(new MkdirAndSymlinkFileStep(getManifest(), getAndroidManifestXml()));

    final AndroidTransitiveDependencies transitiveDependencies = findTransitiveDependencies(
        context.getDependencyGraph());

    final AndroidDexTransitiveDependencies dexTransitiveDependencies =
        findDexTransitiveDependencies(context.getDependencyGraph());

    Set<String> resDirectories = transitiveDependencies.resDirectories;
    Set<String> rDotJavaPackages = transitiveDependencies.rDotJavaPackages;

    FilterResourcesStep filterResourcesStep = null;
    if (requiresResourceFilter()) {
      filterResourcesStep = getFilterResourcesStep(resDirectories);
      commands.add(filterResourcesStep);
      resDirectories = filterResourcesStep.getOutputResourceDirs();
    }

    // Extract the resources from third-party jars.
    // TODO(mbolin): The results of this should be cached between runs.
    Path extractedResourcesDir = getBinPath("__resources__%s__");
    commands.add(new MakeCleanDirectoryStep(extractedResourcesDir));
    commands.add(new ExtractResourcesStep(dexTransitiveDependencies.pathsToThirdPartyJars,
        extractedResourcesDir.toString()));

    // Create the R.java files. Their compiled versions must be included in classes.dex.
    // TODO(mbolin): Skip this step if the transitive set of AndroidResourceRules is cached.
    if (!resDirectories.isEmpty()) {
      UberRDotJavaUtil.generateRDotJavaFiles(resDirectories,
          rDotJavaPackages,
          getBuildTarget(),
          commands);

      if (isStoreStringsAsAssets()) {
        Path tmpStringsDirPath = getPathForTmpStringAssetsDirectory();
        commands.add(new MakeCleanDirectoryStep(tmpStringsDirPath));
        commands.add(new CompileStringsStep(
            filterResourcesStep,
            Paths.get(UberRDotJavaUtil.getPathToGeneratedRDotJavaSrcFiles(getBuildTarget())),
            tmpStringsDirPath));
      }
    }

    // Execute preprocess_java_classes_binary, if appropriate.
    ImmutableSet<String> classpathEntriesToDex;
    if (preprocessJavaClassesBash.isPresent()) {
      // Symlink everything in dexTransitiveDependencies.classpathEntriesToDex to the input
      // directory. Expect parallel outputs in the output directory and update classpathEntriesToDex
      // to reflect that.
      final Path preprocessJavaClassesInDir = getBinPath("java_classes_preprocess_in_%s");
      final Path preprocessJavaClassesOutDir = getBinPath("java_classes_preprocess_out_%s");
      commands.add(new MakeCleanDirectoryStep(preprocessJavaClassesInDir));
      commands.add(new MakeCleanDirectoryStep(preprocessJavaClassesOutDir));
      commands.add(new SymlinkFilesIntoDirectoryStep(
          Paths.get("."),
          dexTransitiveDependencies.classpathEntriesToDex,
          preprocessJavaClassesInDir
          ));
      classpathEntriesToDex = FluentIterable.from(dexTransitiveDependencies.classpathEntriesToDex)
          .transform(new Function<String, String>() {
            @Override
            public String apply(String classpathEntry) {
              return preprocessJavaClassesOutDir.resolve(classpathEntry).toString();
            }
          })
          .toSet();

      AbstractGenruleStep.CommandString commandString = new AbstractGenruleStep.CommandString(
          /* cmd */ Optional.<String>absent(),
          /* bash */ preprocessJavaClassesBash,
          /* cmdExe */ Optional.<String>absent());
      commands.add(new AbstractGenruleStep(this, commandString, preprocessJavaClassesDeps) {

        @Override
        protected void addEnvironmentVariables(
            ExecutionContext context,
            ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
          environmentVariablesBuilder.put("IN_JARS_DIR", preprocessJavaClassesInDir.toString());
          environmentVariablesBuilder.put("OUT_JARS_DIR", preprocessJavaClassesOutDir.toString());
        }

      });

    } else {
      classpathEntriesToDex = dexTransitiveDependencies.classpathEntriesToDex;
    }

    // Execute proguard if desired (transforms input classpaths).
    if (packageType.isBuildWithObfuscation()) {
      classpathEntriesToDex = addProguardCommands(
          context,
          classpathEntriesToDex,
          transitiveDependencies.proguardConfigs,
          commands,
          resDirectories);
    }

    // Create the final DEX (or set of DEX files in the case of split dex).
    // The APK building command needs to take a directory of raw files, so we create a directory
    // that can only contain .dex files from this build rule.
    Path dexDir = getBinPath(".dex/%s");
    commands.add(new MkdirStep(dexDir));
    String dexFile = String.format("%s/classes.dex", dexDir);

    final ImmutableSet.Builder<String> secondaryDexDirectories = ImmutableSet.builder();

    // Create dex artifacts. This may modify assetsDirectories.
    if (preDexDeps.isEmpty()) {
      addDexingCommands(
          classpathEntriesToDex,
          secondaryDexDirectories,
          commands,
          dexFile,
          context.getSourcePathResolver());
    } else {
      Iterable<Path> filesToDex = FluentIterable.from(preDexDeps)
          .transform(
              new Function<DexProducedFromJavaLibraryThatContainsClassFiles, Path>() {
                  @Override
                  @Nullable
                  public Path apply(DexProducedFromJavaLibraryThatContainsClassFiles preDex) {
                    if (preDex.hasOutput()) {
                      return preDex.getPathToDex();
                    } else {
                      return null;
                    }
                  }
              })
          .filter(Predicates.notNull());

      // If this APK has Android resources, then the generated R.class files also need to be dexed.
      if (dexTransitiveDependencies.pathToCompiledRDotJavaFiles.isPresent()) {
        Path pathToCompiledRDotJavaFilesDirectory =
            dexTransitiveDependencies.pathToCompiledRDotJavaFiles.get();
        filesToDex = Iterables.concat(filesToDex,
            Collections.singleton(pathToCompiledRDotJavaFilesDirectory));
      }

      // This will combine the pre-dexed files and the R.class files into a single classes.dex file.
      commands.add(new DxStep(dexFile,
          filesToDex,
          /* options */ EnumSet.of(DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE)));
    }

    // Copy the transitive closure of files in assets to a single directory, if any.
    final ImmutableMap<String, File> extraAssets = extraAssetsBuilder.build();
    Step collectAssets = new Step() {
      @Override
      public int execute(ExecutionContext context) {
        // This must be done in a Command because the files and directories that are specified may
        // not exist at the time this Command is created because the previous Commands have not run
        // yet.
        ImmutableList.Builder<Step> commands = ImmutableList.builder();
        try {
          createAllAssetsDirectory(
              transitiveDependencies.assetsDirectories,
              extraAssets,
              commands,
              new DefaultDirectoryTraverser());
        } catch (IOException e) {
          e.printStackTrace(context.getStdErr());
          return 1;
        }

        for (Step command : commands.build()) {
          int exitCode = command.execute(context);
          if (exitCode != 0) {
            throw new HumanReadableException("Error running " + command.getDescription(context));
          }
        }

        return 0;
      }

      @Override
      public String getShortName() {
        return "symlink_assets";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return getShortName();
      }
    };
    commands.add(collectAssets);

    // Copy the transitive closure of files in native_libs to a single directory, if any.
    ImmutableSet.Builder<String> nativeLibraryDirectories = ImmutableSet.builder();
    if (!transitiveDependencies.nativeLibsDirectories.isEmpty()) {
      Path pathForNativeLibs = getPathForNativeLibs();
      String libSubdirectory = pathForNativeLibs + "/lib";
      nativeLibraryDirectories.add(libSubdirectory);
      commands.add(new MakeCleanDirectoryStep(libSubdirectory));
      for (String nativeLibDir : transitiveDependencies.nativeLibsDirectories) {
        copyNativeLibrary(nativeLibDir, libSubdirectory, commands);
      }
    }

    // Create the unsigned APK.
    String resourceApkPath = getResourceApkPath();
    String unsignedApkPath = getUnsignedApkPath();

    Optional<Path> assetsDirectory;
    if (transitiveDependencies.assetsDirectories.isEmpty() && extraAssets.isEmpty()
        && transitiveDependencies.nativeLibAssetsDirectories.isEmpty()
        && !isStoreStringsAsAssets()) {
      assetsDirectory = Optional.absent();
    } else {
      assetsDirectory = Optional.of(getPathToAllAssetsDirectory());
    }

    if (!transitiveDependencies.nativeLibAssetsDirectories.isEmpty()) {
      String nativeLibAssetsDir = assetsDirectory.get() + "/lib";
      commands.add(new MakeCleanDirectoryStep(nativeLibAssetsDir));
      for (String nativeLibDir : transitiveDependencies.nativeLibAssetsDirectories) {
        copyNativeLibrary(nativeLibDir, nativeLibAssetsDir, commands);
      }
    }

    if (isStoreStringsAsAssets()) {
      Path stringAssetsDir = assetsDirectory.get().resolve("strings");
      commands.add(new MakeCleanDirectoryStep(stringAssetsDir));
      commands.add(new CopyStep(
          getPathForTmpStringAssetsDirectory(),
          stringAssetsDir,
          /* shouldRecurse */ true));
    }

    commands.add(new MkdirStep(outputGenDirectory));

    if (!canSkipAaptResourcePackaging()) {
      AaptStep aaptCommand = new AaptStep(
          getAndroidManifestXml(),
          resDirectories,
          assetsDirectory,
          resourceApkPath,
          ImmutableSet.of(extractedResourcesDir),
          packageType.isCrunchPngFiles());
      commands.add(aaptCommand);
    }

    // Due to limitations of Froyo, we need to ensure that all secondary zip files are STORED in
    // the final APK, not DEFLATED.  The only way to ensure this with ApkBuilder is to zip up the
    // the files properly and then add the zip files to the apk.
    ImmutableSet.Builder<String> secondaryDexZips = ImmutableSet.builder();
    for (String secondaryDexDirectory : secondaryDexDirectories.build()) {
      // String the trailing slash from the directory name and add the zip extension.
      String zipFile = secondaryDexDirectory.replaceAll("/$", "") + ".zip";

      secondaryDexZips.add(zipFile);
      commands.add(new ZipDirectoryWithMaxDeflateStep(secondaryDexDirectory,
          zipFile,
          FROYO_DEFLATE_LIMIT_BYTES));
    }

    ApkBuilderStep apkBuilderCommand = new ApkBuilderStep(
        resourceApkPath,
        unsignedApkPath,
        dexFile,
        ImmutableSet.<String>of(),
        nativeLibraryDirectories.build(),
        secondaryDexZips.build(),
        false);
    commands.add(apkBuilderCommand);

    // Sign the APK.
    String signedApkPath = getSignedApkPath();
    SignApkStep signApkStep = new SignApkStep(
        keystore.getPathToStore(), keystore.getPathToPropertiesFile(), unsignedApkPath, signedApkPath);
    commands.add(signApkStep);

    String apkToAlign;

    // Optionally, compress the resources file in the .apk.
    if (this.isCompressResources()) {
      String compressedApkPath = getCompressedResourcesApkPath();
      apkToAlign = compressedApkPath;
      RepackZipEntriesStep arscComp = new RepackZipEntriesStep(
          signedApkPath,
          compressedApkPath,
          ImmutableSet.of("resources.arsc"));
      commands.add(arscComp);
    } else {
      apkToAlign = signedApkPath;
    }

    Path apkPath = getApkPath();
    ZipalignStep zipalign = new ZipalignStep(apkToAlign, apkPath);
    commands.add(zipalign);

    // Inform the user where the APK can be found.
    EchoStep success = new EchoStep(
        String.format("built APK for %s at %s", getFullyQualifiedName(), apkPath));
    commands.add(success);

    return commands.build();
  }

  /**
   * Given a set of assets directories to include in the APK (which may be empty), return the path
   * to the directory that contains the union of all the assets. If any work needs to be done to
   * create such a directory, the appropriate commands should be added to the {@code commands}
   * list builder.
   * <p>
   * If there are no assets (i.e., {@code assetsDirectories} is empty), then the return value will
   * be an empty {@link Optional}.
   */
  @VisibleForTesting
  Optional<Path> createAllAssetsDirectory(
      Set<String> assetsDirectories,
      ImmutableMap<String, File> extraAssets,
      ImmutableList.Builder<Step> commands,
      DirectoryTraverser traverser) throws IOException {
    if (assetsDirectories.isEmpty() && extraAssets.isEmpty()) {
      return Optional.absent();
    }

    // Due to a limitation of aapt, only one assets directory can be specified, so if multiple are
    // specified in Buck, then all of the contents must be symlinked to a single directory.
    Path destination = getPathToAllAssetsDirectory();
    commands.add(new MakeCleanDirectoryStep(destination));
    final ImmutableMap.Builder<String, File> allAssets = ImmutableMap.builder();

    File destinationDirectory = destination.toFile();
    for (String assetsDirectory : assetsDirectories) {
      traverser.traverse(new DirectoryTraversal(new File(assetsDirectory)) {
        @Override
        public void visit(File file, String relativePath) {
          allAssets.put(relativePath, file);
        }
      });
    }

    allAssets.putAll(extraAssets);

    for (Map.Entry<String, File> entry : allAssets.build().entrySet()) {
      commands.add(new MkdirAndSymlinkFileStep(
          MorePaths.newPathInstance(entry.getValue()).toString(),
          MorePaths.newPathInstance(destinationDirectory + "/" + entry.getKey()).toString()));
    }

    return Optional.of(destination);
  }

  public AndroidTransitiveDependencies findTransitiveDependencies(DependencyGraph graph) {
    return getTransitiveDependencyGraph().findDependencies(getAndroidResourceDepsInternal(graph));
  }

  public AndroidDexTransitiveDependencies findDexTransitiveDependencies(DependencyGraph graph) {
    return getTransitiveDependencyGraph().findDexDependencies(
        getAndroidResourceDepsInternal(graph),
        buildRulesToExcludeFromDex);
  }

  /**
   * @return a list of {@link HasAndroidResourceDeps}s that should be passed, in order, to {@code aapt}
   *     when generating the {@code R.java} files for this APK.
   */
  protected ImmutableList<HasAndroidResourceDeps> getAndroidResourceDepsInternal(
      DependencyGraph graph) {
    return UberRDotJavaUtil.getAndroidResourceDeps(this, graph);
  }

  private boolean canSkipAaptResourcePackaging() {
    // TODO(mbolin): Create a RuleKey for resources only and use it to determine the value of this
    // boolean. Whether the resources have not changed since the last build run is irrelevant
    // because this AndroidBinary may not have been written as part of the last build run.
    return false;
  }

  /**
   * This is the path to the directory for generated files related to ProGuard. Ultimately, it
   * should include:
   * <ul>
   *   <li>proguard.txt
   *   <li>dump.txt
   *   <li>seeds.txt
   *   <li>usage.txt
   *   <li>mapping.txt
   *   <li>obfuscated.jar
   * </ul>
   * @return path to directory (will not include trailing slash)
   */
  @VisibleForTesting
  Path getPathForProGuardDirectory() {
    return MorePaths.newPathInstance(
        String.format("%s/%s.proguard/%s",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePathWithSlash(),
        getBuildTarget().getShortName()));
  }

  @VisibleForTesting
  Path getPathToAllAssetsDirectory() {
    return getBinPath("__assets_%s__");
  }

  private Path getPathForTmpStringAssetsDirectory() {
    return getBinPath("__strings_%s__");
  }

  /**
   * All native libs are copied to this directory before running aapt.
   */
  private Path getPathForNativeLibs() {
    return getBinPath("__native_libs_%s__");
  }

  public Keystore getKeystore() {
    return keystore;
  }

  public String getResourceApkPath() {
    return String.format("%s%s.unsigned.ap_",
        outputGenDirectory,
        getBuildTarget().getShortName());
  }

  public String getUnsignedApkPath() {
    return String.format("%s%s.unsigned.apk",
        outputGenDirectory,
        getBuildTarget().getShortName());
  }

  /** The APK at this path will be signed, but not zipaligned. */
  private String getSignedApkPath() {
    return getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".signed.apk");
  }

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  private String getCompressedResourcesApkPath() {
    return getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".compressed.apk");
  }

  /**
   * Buck does not require the manifest to be named AndroidManifest.xml, but commands such as aapt
   * do. For this reason, we symlink the path to {@link #getManifest()} to the path returned by
   * this method, whose name is always "AndroidManifest.xml".
   * <p>
   * Therefore, commands created by this method should use this method instead of
   * {@link #getManifest()}.
   */
  private Path getAndroidManifestXml() {
    return getBinPath("__manifest_%s__/AndroidManifest.xml");
  }

  /**
   * Return a path to a file in the buck-out/bin/ directory. {@code format} will be prepended with
   * the {@link BuckConstant#BIN_DIR} and the target base path, then formatted with the target
   * short name.
   * {@code format} should not start with a slash.
   */
  private Path getBinPath(String format) {
    return MorePaths.newPathInstance(String.format("%s/%s" + format,
        BuckConstant.BIN_DIR,
        getBuildTarget().getBasePathWithSlash(),
        getBuildTarget().getShortName()));
  }

  @VisibleForTesting
  Path getProguardOutputFromInputClasspath(String classpathEntry) {
    // Hehe, this is so ridiculously fragile.
    Preconditions.checkArgument(classpathEntry.charAt(0) != '/',
        "Classpath entries should be relative rather than absolute paths: %s",
        classpathEntry);
    String obfuscatedName = Files.getNameWithoutExtension(classpathEntry) + "-obfuscated.jar";
    Path dirName = MorePaths.newPathInstance(new File(classpathEntry).getParent());
    Path outputJar = getPathForProGuardDirectory().resolve(dirName).resolve(obfuscatedName);
    return outputJar;
  }

  /**
   * @return the resulting set of ProGuarded classpath entries to dex.
   */
  @VisibleForTesting
  ImmutableSet<String> addProguardCommands(
      BuildContext context,
      Set<String> classpathEntriesToDex,
      Set<String> depsProguardConfigs,
      ImmutableList.Builder<Step> commands,
      Set<String> resDirectories) {
    final ImmutableSetMultimap<JavaLibraryRule, String> classpathEntriesMap =
        getTransitiveClasspathEntries();
    ImmutableSet.Builder<String> additionalLibraryJarsForProguardBuilder = ImmutableSet.builder();

    for (BuildRule buildRule : buildRulesToExcludeFromDex) {
      if (buildRule instanceof JavaLibraryRule) {
        additionalLibraryJarsForProguardBuilder.addAll(
            classpathEntriesMap.get((JavaLibraryRule)buildRule));
      }
    }

    // Clean out the directory for generated ProGuard files.
    Path proguardDirectory = getPathForProGuardDirectory();
    commands.add(new MakeCleanDirectoryStep(proguardDirectory));

    // Generate a file of ProGuard config options using aapt.
    String generatedProGuardConfig = proguardDirectory + "/proguard.txt";
    GenProGuardConfigStep genProGuardConfig = new GenProGuardConfigStep(
        getAndroidManifestXml(),
        resDirectories,
        generatedProGuardConfig);
    commands.add(genProGuardConfig);

    // Create list of proguard Configs for the app project and its dependencies
    ImmutableSet.Builder<String> proguardConfigsBuilder = ImmutableSet.builder();
    proguardConfigsBuilder.addAll(depsProguardConfigs);
    if (proguardConfig.isPresent()) {
      proguardConfigsBuilder.add(proguardConfig.get().resolve(context).toString());
    }

    // Transform our input classpath to a set of output locations for each input classpath.
    // TODO(devjasta): the output path we choose is the result of a slicing function against
    // input classpath. This is fragile and should be replaced with knowledge of the BuildTarget.
    final ImmutableMap<String, String> inputOutputEntries = FluentIterable
        .from(classpathEntriesToDex)
        .toMap(new Function<String, String>() {
          @Override
          public String apply(String classpathEntry) {
            return getProguardOutputFromInputClasspath(classpathEntry).toString();
          }
        });

    // Run ProGuard on the classpath entries.
    // TODO: ProGuardObfuscateStep's final argument should be a Path
    Step obfuscateCommand = ProGuardObfuscateStep.create(
        generatedProGuardConfig,
        proguardConfigsBuilder.build(),
        useAndroidProguardConfigWithOptimizations,
        inputOutputEntries,
        additionalLibraryJarsForProguardBuilder.build(),
        proguardDirectory.toString());
    commands.add(obfuscateCommand);

    // Apply the transformed inputs to the classpath (this will modify deps.classpathEntriesToDex
    // so that we're now dexing the proguarded artifacts).
    return ImmutableSet.copyOf(inputOutputEntries.values());
  }

  /**
   * Create dex artifacts for all of the individual directories of compiled .class files (or
   * the obfuscated jar files if proguard is used).  If split dex is used, multiple dex artifacts
   * will be produced.
   *
   * @param classpathEntriesToDex Full set of classpath entries that must make
   *     their way into the final APK structure (but not necessarily into the
   *     primary dex).
   * @param commands
   * @param primaryDexPath Output path for the primary dex file.
   */
  @VisibleForTesting
  void addDexingCommands(
      Set<String> classpathEntriesToDex,
      ImmutableSet.Builder<String> secondaryDexDirectories,
      ImmutableList.Builder<Step> commands,
      String primaryDexPath,
      Function<SourcePath, Path> sourcePathResolver) {
    final Set<String> primaryInputsToDex;
    final Optional<String> secondaryDexDir;
    final Optional<String> secondaryInputsDir;

    if (shouldSplitDex()) {
      Optional<Path> proguardMappingFile = Optional.absent();
      if (packageType.isBuildWithObfuscation()) {
        proguardMappingFile = Optional.of(getPathForProGuardDirectory().resolve("mapping.txt"));
      }

      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.
      String magicSecondaryDexSubdir = "assets/secondary-program-dex-jars";

      // Intermediate directory holding the primary split-zip jar.
      Path splitZipDir = getBinPath("__%s_split_zip__");
      commands.add(new MakeCleanDirectoryStep(splitZipDir));
      String primaryJarPath = splitZipDir + "/primary.jar";

      String secondaryJarMetaDirParent = splitZipDir + "/secondary_meta/";
      String secondaryJarMetaDir = secondaryJarMetaDirParent + magicSecondaryDexSubdir;
      commands.add(new MakeCleanDirectoryStep(secondaryJarMetaDir));
      String secondaryJarMeta = secondaryJarMetaDir + "/metadata.txt";

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      Path secondaryZipDir = getBinPath("__%s_secondary_zip__");
      commands.add(new MakeCleanDirectoryStep(secondaryZipDir));

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      Path zipSplitReportDir = getBinPath("__%s_split_zip_report__");
      commands.add(new MakeCleanDirectoryStep(zipSplitReportDir));
      SplitZipStep splitZipCommand = new SplitZipStep(
          classpathEntriesToDex,
          secondaryJarMeta,
          primaryJarPath,
          secondaryZipDir,
          "secondary-%d.jar",
          proguardMappingFile,
          primaryDexSubstrings,
          primaryDexClassesFile.transform(sourcePathResolver),
          dexSplitMode.getDexSplitStrategy(),
          dexSplitMode.getDexStore(),
          zipSplitReportDir,
          dexSplitMode.useLinearAllocSplitDex(),
          linearAllocHardLimit);
      commands.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      Path secondaryDexParentDir = getBinPath("__%s_secondary_dex__/");
      secondaryDexDir = Optional.of(secondaryDexParentDir + magicSecondaryDexSubdir);
      commands.add(new MkdirStep(secondaryDexDir.get()));

      secondaryDexDirectories.add(secondaryJarMetaDirParent);
      secondaryDexDirectories.add(secondaryDexParentDir.toString());

      // Adjust smart-dex inputs for the split-zip case.
      primaryInputsToDex = ImmutableSet.of(primaryJarPath);
      secondaryInputsDir = Optional.of(secondaryZipDir.toString());
    } else {
      // Simple case where our inputs are the natural classpath directories and we don't have
      // to worry about secondary jar/dex files.
      primaryInputsToDex = classpathEntriesToDex;
      secondaryDexDir = Optional.absent();
      secondaryInputsDir = Optional.absent();
    }

    // Stores checksum information from each invocation to intelligently decide when dx needs
    // to be re-run.
    Path successDir = getBinPath("__%s_smart_dex__/.success");
    commands.add(new MkdirStep(successDir));

    // Add the smart dexing tool that is capable of avoiding the external dx invocation(s) if
    // it can be shown that the inputs have not changed.  It also parallelizes dx invocations
    // where applicable.
    //
    // Note that by not specifying the number of threads this command will use it will select an
    // optimal default regardless of the value of --num-threads.  This decision was made with the
    // assumption that --num-threads specifies the threading of build rule execution and does not
    // directly apply to the internal threading/parallelization details of various build commands
    // being executed.  For example, aapt is internally threaded by default when preprocessing
    // images.
    SmartDexingStep smartDexingCommand = new SmartDexingStep(
        primaryDexPath,
        primaryInputsToDex,
        secondaryDexDir,
        secondaryInputsDir,
        successDir,
        Optional.<Integer>absent(),
        dexSplitMode.getDexStore(),
        /* optimize */ PackageType.RELEASE.equals(packageType));
    commands.add(smartDexingCommand);
  }

  /**
   * @return the path to the AndroidManifest.xml. Note that this file is not guaranteed to be named
   *     AndroidManifest.xml.
   */
  @Override
  public Path getManifest() {
    return manifest;
  }

  String getTarget() {
    return target;
  }

  boolean shouldSplitDex() {
    return dexSplitMode.isShouldSplitDex();
  }

  boolean isUseAndroidProguardConfigWithOptimizations() {
    return useAndroidProguardConfigWithOptimizations;
  }

  ImmutableSet<String> getPrimaryDexSubstrings() {
    return primaryDexSubstrings;
  }

  long getLinearAllocHardLimit() {
    return linearAllocHardLimit;
  }

  Optional<SourcePath> getPrimaryDexClassesFile() {
    return primaryDexClassesFile;
  }

  public ImmutableSortedSet<BuildRule> getClasspathDeps() {
    return classpathDeps;
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
    // This is used primarily for buck audit classpath.
    return Classpaths.getClasspathEntries(classpathDeps);
  }

  public static Builder newAndroidBinaryRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<AndroidBinaryRule> {
    private static final PackageType DEFAULT_PACKAGE_TYPE = PackageType.DEBUG;

    private Path manifest;
    private String target;

    /** This should always be a subset of {@link #getDeps()}. */
    private ImmutableSet.Builder<BuildTarget> classpathDeps = ImmutableSet.builder();

    private BuildTarget keystoreTarget;
    private PackageType packageType = DEFAULT_PACKAGE_TYPE;
    private Set<BuildTarget> buildRulesToExcludeFromDex = Sets.newHashSet();
    private boolean disablePreDex = false;
    private DexSplitMode dexSplitMode = new DexSplitMode(
        /* shouldSplitDex */ false,
        ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
        DexStore.JAR,
        /* useLinearAllocSplitDex */ false);
    private boolean useAndroidProguardConfigWithOptimizations = false;
    private Optional<SourcePath> proguardConfig = Optional.absent();
    private ResourceCompressionMode resourceCompressionMode = ResourceCompressionMode.DISABLED;
    private ImmutableSet.Builder<String> primaryDexSubstrings = ImmutableSet.builder();
    private long linearAllocHardLimit = 0;
    private Optional<SourcePath> primaryDexClassesFile = Optional.absent();
    private FilterResourcesStep.ResourceFilter resourceFilter =
        new FilterResourcesStep.ResourceFilter(ImmutableList.<String>of());
    private ImmutableSet.Builder<TargetCpuType> cpuFilters = ImmutableSet.builder();
    private ImmutableSet.Builder<BuildTarget> preprocessJavaClassesDeps = ImmutableSet.builder();
    private Optional<String> preprocessJavaClassesBash = Optional.absent();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public AndroidBinaryRule build(BuildRuleResolver ruleResolver) {
      // Make sure the "keystore" argument refers to a KeystoreRule.
      BuildRule rule = ruleResolver.get(keystoreTarget);

      Buildable keystore = rule.getBuildable();
      if (!(keystore instanceof Keystore)) {
        throw new HumanReadableException(
            "In %s, keystore='%s' must be a keystore() but was %s().",
            getBuildTarget(),
            rule.getFullyQualifiedName(),
            rule.getType().getName());
      }

      BuildRuleParams originalParams = createBuildRuleParams(ruleResolver);
      ImmutableSortedSet<BuildRule> originalDeps = originalParams.getDeps();
      ImmutableSet<BuildRule> preDexDepsAsBuildRules;
      ImmutableSet<DexProducedFromJavaLibraryThatContainsClassFiles> preDexDeps;
      if (!disablePreDex
          && PackageType.DEBUG.equals(packageType)
          && !dexSplitMode.isShouldSplitDex() // TODO(mbolin): Support predex for split dex.
          && !preprocessJavaClassesBash.isPresent() // TODO(mbolin): Support predex post-preprocess.
          ) {
        preDexDepsAsBuildRules = enhanceGraphToLeveragePreDexing(originalDeps,
            buildRulesToExcludeFromDex,
            ruleResolver,
            originalParams.getPathRelativizer(),
            originalParams.getRuleKeyBuilderFactory());
        ImmutableSet.Builder<DexProducedFromJavaLibraryThatContainsClassFiles> preDexDepsBuilder =
            ImmutableSet.builder();
        for (BuildRule preDexDepBuildRule : preDexDepsAsBuildRules) {
          preDexDepsBuilder.add(
              (DexProducedFromJavaLibraryThatContainsClassFiles) preDexDepBuildRule.getBuildable());
        }
        preDexDeps = preDexDepsBuilder.build();
      } else {
        preDexDepsAsBuildRules = ImmutableSet.of();
        preDexDeps = ImmutableSortedSet.of();
      }

      boolean allowNonExistentRule =
          false;

      // Must create a new BuildRuleParams to supersede the one built by
      // createBuildRuleParams(ruleResolver).
      ImmutableSortedSet<BuildRule> totalDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(originalDeps)
          .addAll(preDexDepsAsBuildRules)
          .build();
      BuildRuleParams finalParams = new BuildRuleParams(getBuildTarget(),
          totalDeps,
          originalParams.getVisibilityPatterns(),
          originalParams.getPathRelativizer(),
          originalParams.getRuleKeyBuilderFactory());

      return new AndroidBinaryRule(
          finalParams,
          manifest,
          target,
          getBuildTargetsAsBuildRules(ruleResolver, classpathDeps.build()),
          (Keystore)keystore,
          packageType,
          /* buildRulesToExcludeFromDex */ getBuildTargetsAsBuildRules(ruleResolver,
              buildRulesToExcludeFromDex,
              allowNonExistentRule),
          dexSplitMode,
          useAndroidProguardConfigWithOptimizations,
          proguardConfig,
          resourceCompressionMode,
          primaryDexSubstrings.build(),
          linearAllocHardLimit,
          primaryDexClassesFile,
          resourceFilter,
          cpuFilters.build(),
          preDexDeps,
          getBuildTargetsAsBuildRules(ruleResolver, preprocessJavaClassesDeps.build()),
          preprocessJavaClassesBash);
    }

    /**
     * @return The set of build rules that correspond to pre-dex'd artifacts that should be merged
     *     to create the final classes.dex for the APK. For every element in the set, its
     *     {@link BuildRule#getBuildable()} method will return an instance of
     *     {@link DexProducedFromJavaLibraryThatContainsClassFiles}.
     */
    private ImmutableSet<BuildRule> enhanceGraphToLeveragePreDexing(
            ImmutableSortedSet<BuildRule> originalDeps,
            Set<BuildTarget> buildRulesToExcludeFromDex,
            BuildRuleResolver ruleResolver,
            Function<String, Path> pathRelativizer,
            RuleKeyBuilderFactory ruleKeyBuilderFactory) {
      ImmutableSet.Builder<BuildRule> preDexDeps = ImmutableSet.builder();
      ImmutableSet<JavaLibraryRule> transitiveJavaDeps = Classpaths
          .getClasspathEntries(originalDeps).keySet();
      for (JavaLibraryRule javaLibraryRule : transitiveJavaDeps) {
        // If the rule has no output file (which happens when a java_library has no srcs or
        // resources, but export_deps is true), then there will not be anything to dx.
        if (javaLibraryRule.getPathToOutputFile() == null) {
          continue;
        }

        // If the rule is in the no_dx list, then do not pre-dex it.
        if (buildRulesToExcludeFromDex.contains(javaLibraryRule.getBuildTarget())) {
          continue;
        }

        // See whether the corresponding PreDex has already been added to the ruleResolver.
        BuildTarget originalTarget = javaLibraryRule.getBuildTarget();
        BuildTarget preDexTarget = new BuildTarget(originalTarget.getBaseName(),
            originalTarget.getShortName(),
            "dex");
        BuildRule preDexRule = ruleResolver.get(preDexTarget);
        if (preDexRule != null) {
          preDexDeps.add(preDexRule);
          continue;
        }

        // Create a rule to get the list of the classes in the JavaLibraryRule.
        AccumulateClassNames.Builder accumulateClassNamesBuilder = AccumulateClassNames
            .newAccumulateClassNamesBuilder(new DefaultBuildRuleBuilderParams(
                pathRelativizer, ruleKeyBuilderFactory));
        BuildTarget accumulateClassNamesBuildTarget = new BuildTarget(
            originalTarget.getBaseName(), originalTarget.getShortName(), "class_names");
        accumulateClassNamesBuilder.setBuildTarget(accumulateClassNamesBuildTarget);
        accumulateClassNamesBuilder.setJavaLibraryToDex(javaLibraryRule);
        accumulateClassNamesBuilder.addDep(originalTarget);
        accumulateClassNamesBuilder.addVisibilityPattern(BuildTargetPattern.MATCH_ALL);
        BuildRule accumulateClassNamesRule = ruleResolver.buildAndAddToIndex(
            accumulateClassNamesBuilder);
        AccumulateClassNames accumulateClassNames =
            (AccumulateClassNames) accumulateClassNamesRule.getBuildable();

        // Create the PreDex and add it to both the ruleResolver and preDexDeps.
        DexProducedFromJavaLibraryThatContainsClassFiles.Builder preDexBuilder =
            DexProducedFromJavaLibraryThatContainsClassFiles.newPreDexBuilder(
                new DefaultBuildRuleBuilderParams(
                    pathRelativizer,
                    ruleKeyBuilderFactory));
        preDexBuilder.setBuildTarget(preDexTarget);
        preDexBuilder.setPathToClassNamesList(accumulateClassNames);
        preDexBuilder.addDep(accumulateClassNamesBuildTarget);
        preDexBuilder.addVisibilityPattern(BuildTargetPattern.MATCH_ALL);
        BuildRule preDex = ruleResolver.buildAndAddToIndex(preDexBuilder);
        preDexDeps.add(preDex);
      }
      return preDexDeps.build();
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }

    public Builder setManifest(String manifest) {
      this.manifest = MorePaths.newPathInstance(manifest);
      return this;
    }

    public Builder setTarget(String target) {
      this.target = target;
      return this;
    }

    public Builder addClasspathDep(BuildTarget classpathDep) {
      this.classpathDeps.add(classpathDep);
      addDep(classpathDep);
      return this;
    }

    public Builder setKeystore(BuildTarget keystoreTarget) {
      this.keystoreTarget = keystoreTarget;
      addDep(keystoreTarget);
      return this;
    }

    public Builder setPackageType(String packageType) {
      if (packageType == null) {
        this.packageType = DEFAULT_PACKAGE_TYPE;
      } else {
        this.packageType = PackageType.valueOf(packageType.toUpperCase());
      }
      return this;
    }

    public Builder addBuildRuleToExcludeFromDex(BuildTarget entry) {
      this.buildRulesToExcludeFromDex.add(entry);
      return this;
    }

    public Builder setDisablePreDex(boolean disablePreDex) {
      this.disablePreDex = disablePreDex;
      return this;
    }

    public Builder setDexSplitMode(DexSplitMode dexSplitMode) {
      this.dexSplitMode = dexSplitMode;
      return this;
    }

    public Builder setUseAndroidProguardConfigWithOptimizations(
        boolean useAndroidProguardConfigWithOptimizations) {
      this.useAndroidProguardConfigWithOptimizations = useAndroidProguardConfigWithOptimizations;
      return this;
    }

    public Builder setProguardConfig(Optional<SourcePath> proguardConfig) {
      this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
      return this;
    }

    public Builder addPrimaryDexSubstrings(Iterable<String> primaryDexSubstrings) {
      this.primaryDexSubstrings.addAll(primaryDexSubstrings);
      return this;
    }

    public Builder setLinearAllocHardLimit(long linearAllocHardLimit) {
      this.linearAllocHardLimit = linearAllocHardLimit;
      return this;
    }

    public Builder setPrimaryDexClassesFile(Optional<SourcePath> primaryDexClassesFile) {
      this.primaryDexClassesFile = Preconditions.checkNotNull(primaryDexClassesFile);
      return this;
    }

    public Builder setResourceFilter(ResourceFilter resourceFilter) {
      this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
      return this;
    }

    public Builder setResourceCompressionMode(String resourceCompressionMode) {
      Preconditions.checkNotNull(resourceCompressionMode);
      try {
        this.resourceCompressionMode = ResourceCompressionMode.valueOf(
            resourceCompressionMode.toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new HumanReadableException(String.format(
            "In %s, android_binary() was passed an invalid resource compression mode: %s",
            buildTarget.getFullyQualifiedName(),
            resourceCompressionMode));
      }
      return this;
    }

    public Builder addCpuFilter(String cpuFilter) {
      if (cpuFilter != null) {
        try {
          this.cpuFilters.add(TargetCpuType.valueOf(cpuFilter.toUpperCase()));
        } catch (IllegalArgumentException e) {
          throw new HumanReadableException(
              "android_binary() was passed an invalid cpu filter: " + cpuFilter);
        }
      }
      return this;
    }

    public Builder addPreprocessJavaClassesDep(BuildTarget preprocessJavaClassesDep) {
      this.preprocessJavaClassesDeps.add(preprocessJavaClassesDep);
      this.addDep(preprocessJavaClassesDep);
      return this;
    }

    public Builder setPreprocessJavaClassesBash(
        Optional<String> preprocessJavaClassesBash) {
      this.preprocessJavaClassesBash = Preconditions.checkNotNull(preprocessJavaClassesBash);
      return this;
    }
  }
}
