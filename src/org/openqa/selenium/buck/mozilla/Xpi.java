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

package org.openqa.selenium.buck.mozilla;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;

public class Xpi extends AbstractBuildable {

  private final Path scratch;
  private final Path output;
  private final Path chrome;
  private final ImmutableSortedSet<SourcePath> components;
  private final ImmutableSortedSet<Path> content;
  private final Path install;
  private final ImmutableSortedSet<SourcePath> resources;

  public Xpi(
      BuildTarget target,
      Path chrome,
      ImmutableSortedSet<SourcePath> components,
      ImmutableSortedSet<Path> content,
      Path install, ImmutableSortedSet<SourcePath> resources) {
    this.chrome = chrome;
    this.components = components;
    this.content = content;
    this.install = install;
    this.resources = resources;

    this.output = Paths.get(
        BuckConstant.GEN_DIR, target.getBasePath(), target.getShortName(), target.getShortName() + ".xpi");

    this.scratch = Paths.get(
        BuckConstant.BIN_DIR, target.getBasePath(), target.getShortName() + "-xpi");
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    return Iterables.transform(content, Functions.toStringFunction());
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new MakeCleanDirectoryStep(scratch));

    steps.add(new CopyStep(chrome, scratch.resolve("chrome.manifest")));
    steps.add(new CopyStep(install, scratch.resolve("install.rdf")));

    Path contentDir = scratch.resolve("content");
    steps.add(new MkdirStep(contentDir));
    for (Path item : content) {
      steps.add(new CopyStep(item, contentDir.resolve(item.getFileName()), true));
    }

    Path componentDir = scratch.resolve("components");
    steps.add(new MkdirStep(componentDir));
    for (SourcePath component : components) {
      Path resolved = component.resolve(context);
      steps.add(new CopyStep(resolved, componentDir.resolve(resolved.getFileName())));
    }

    for (SourcePath resource : resources) {
      Path resolved = resource.resolve(context);
      steps.add(new CopyStep(resolved, scratch, true));
    }

    steps.add(new MakeCleanDirectoryStep(output.getParent()));
    steps.add(new ZipStep(
        output.normalize().toAbsolutePath().toString(),
        ImmutableSet.<String>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        scratch.toFile()));

    return steps.build();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .setInputs("chrome", ImmutableSet.of(chrome).iterator())
        .setSourcePaths("components", components)
        .setInputs("content", content.iterator())
        .setInputs("install", ImmutableSet.of(install).iterator())
        .setSourcePaths("resources", resources)
        ;
  }

  @Nullable
  @Override
  public String getPathToOutputFile() {
    return output.toString();
  }
}