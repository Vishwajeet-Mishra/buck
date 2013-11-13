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

package com.facebook.buck.rules;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * No-op implementations of all methods from {@link Buildable}.
 */
public abstract class AbstractBuildable implements Buildable {

  @Override
  public BuildableProperties getProperties() {
    return BuildableProperties.NONE;
  }

  @Nullable
  @Override
  public abstract Path getPathToOutputFile();

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder;
  }

  /**
   * @param onDiskBuildInfo Contains metadata that was read from disk.
   * @see AbstractCachingBuildRule#initializeFromDisk
   */
  protected void initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {

  }

  protected static abstract class Builder extends AbstractBuildRuleBuilder<AbstractCachingBuildRule> {

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    protected abstract BuildRuleType getType();
    protected abstract Buildable newBuildable(BuildRuleParams params, BuildRuleResolver resolver);

    @Override
    public final AbstractCachingBuildRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams params = createBuildRuleParams(ruleResolver);

      final Buildable buildable = newBuildable(params, ruleResolver);
      final BuildRuleType type = getType();

      return new AbstractCachingBuildRule(buildable, params) {
        @Override
        public Buildable getBuildable() {
          return buildable;
        }

        @Override
        public BuildRuleType getType() {
          return type;
        }

        @Override
        protected void initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
          if (buildable instanceof AbstractBuildable) {
            ((AbstractBuildable) buildable).initializeFromDisk(onDiskBuildInfo);
          }
        }
      };
    }
  }
}
