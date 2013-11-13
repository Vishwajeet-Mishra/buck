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

import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class AndroidTransitiveDependencies {
  public final ImmutableSet<String> assetsDirectories;
  public final ImmutableSet<Path> nativeLibsDirectories;
  public final ImmutableSet<Path> nativeLibAssetsDirectories;
  public final ImmutableSet<String> manifestFiles;
  public final ImmutableSet<Path> resDirectories;
  public final ImmutableSet<String> rDotJavaPackages;
  public final ImmutableSet<String> proguardConfigs;

  public AndroidTransitiveDependencies(ImmutableSet<String> assetsDirectories,
                                       ImmutableSet<Path> nativeLibsDirectories,
                                       ImmutableSet<Path> nativeLibAssetsDirectories,
                                       ImmutableSet<String> manifestFiles,
                                       ImmutableSet<Path> resDirectories,
                                       ImmutableSet<String> rDotJavaPackages,
                                       ImmutableSet<String> proguardConfigs) {
    this.assetsDirectories = ImmutableSet.copyOf(assetsDirectories);
    this.nativeLibsDirectories = ImmutableSet.copyOf(nativeLibsDirectories);
    this.nativeLibAssetsDirectories = ImmutableSet.copyOf(nativeLibAssetsDirectories);
    this.manifestFiles = ImmutableSet.copyOf(manifestFiles);
    this.resDirectories = ImmutableSet.copyOf(resDirectories);
    this.rDotJavaPackages = ImmutableSet.copyOf(rDotJavaPackages);
    this.proguardConfigs = ImmutableSet.copyOf(proguardConfigs);
  }
}
