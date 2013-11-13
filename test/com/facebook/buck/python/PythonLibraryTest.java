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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Unit test for {@link PythonLibrary}.
 */
public class PythonLibraryTest {
  @Rule
  public final TemporaryFolder projectRootDir = new TemporaryFolder();

  @Test
  public void testGetters() {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        new BuildTarget("//scripts/python", "foo"));
    ImmutableSortedSet<Path> srcs = ImmutableSortedSet.of(Paths.get(""));
    PythonLibrary pythonLibrary = new PythonLibrary(
        buildRuleParams,
        srcs);

    assertTrue(pythonLibrary.getProperties().is(LIBRARY));
    assertSame(srcs, pythonLibrary.getPythonSrcs());
  }

  @Test
  public void testFlattening() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget pyLibraryTarget = BuildTargetFactory.newInstance("//:py_library");
    ruleResolver.buildAndAddToIndex(
        PythonLibrary.newPythonLibraryBuilder(new FakeAbstractBuildRuleBuilderParams())
            .addSrc(Paths.get("baz.py"))
            .addSrc(Paths.get("foo/__init__.py"))
            .addSrc(Paths.get("foo/bar.py"))
            .setBuildTarget(pyLibraryTarget));
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    BuildContext buildContext = createMock(BuildContext.class);
    PythonLibrary rule = (PythonLibrary)ruleResolver.get(pyLibraryTarget).getBuildable();
    List<Step> steps = rule.getBuildSteps(buildContext, buildableContext);

    final String projectRoot = projectRootDir.getRoot().getAbsolutePath();
    final String pylibpath = "__pylib_py_library";

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File(projectRoot));
    ExecutionContext executionContext = TestExecutionContext.newBuilder()
      .setProjectFilesystem(projectFilesystem)
      .build();

    MoreAsserts.assertSteps(
        "python_library() should ensure each file is linked and has its destination directory made",
        ImmutableList.of(
            String.format(
              "mkdir -p %s/%s/%s",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "mkdir -p %s/%s/%s/foo",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "ln -f -s ../../../baz.py %s/%s/%s/baz.py",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "ln -f -s ../../../../foo/__init__.py %s/%s/%s/foo/__init__.py",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              ),
            String.format(
              "ln -f -s ../../../../foo/bar.py %s/%s/%s/foo/bar.py",
              projectRoot,
              BuckConstant.GEN_DIR,
              pylibpath
              )
        ),
        steps.subList(1, 6),
        executionContext);

    ImmutableSet<Path> artifacts = buildableContext.getRecordedArtifacts();
    assertEquals(
      ImmutableSet.of(
        Paths.get(pylibpath, "baz.py"),
        Paths.get(pylibpath, "foo/__init__.py"),
        Paths.get(pylibpath, "foo/bar.py")
      ),
      artifacts);
  }
}
