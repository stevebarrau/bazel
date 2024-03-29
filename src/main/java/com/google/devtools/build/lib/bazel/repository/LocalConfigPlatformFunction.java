// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.bazel.ResolvedEvent;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.ResolvedFileValue;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Create a local repository that describes the auto-detected host platform. */
public class LocalConfigPlatformFunction extends RepositoryFunction {

  @Override
  public boolean isLocal(Rule rule) {
    return true;
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return LocalConfigPlatformRule.class;
  }

  @Override
  public RepositoryDirectoryValue.Builder fetch(
      Rule rule,
      Path outputDirectory,
      BlazeDirectories directories,
      Environment env,
      Map<RepoRecordedInput, String> recordedInputValues,
      SkyKey key)
      throws RepositoryFunctionException {
    String name = rule.getName();
    try {
      outputDirectory.createDirectoryAndParents();
      RepositoryFunction.writeFile(outputDirectory, "WORKSPACE", workspaceFileContent(name));
      RepositoryFunction.writeFile(outputDirectory, "MODULE.bazel", moduleFileContent(name));
      RepositoryFunction.writeFile(outputDirectory, "BUILD.bazel", buildFileContent());
      RepositoryFunction.writeFile(outputDirectory, "constraints.bzl", constraintFileContent());
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException("Could not create content for " + name + ": " + e.getMessage()),
          Transience.TRANSIENT);
    }

    // Save in the resolved repository file.
    env.getListener()
        .post(
            new ResolvedEvent() {
              @Override
              public String getName() {
                return name;
              }

              @Override
              public Object getResolvedInformation(XattrProvider xattrProvider) {
                String repr = String.format("local_config_platform(name = '%s')", name);
                return ImmutableMap.<String, Object>builder()
                    .put(ResolvedFileValue.ORIGINAL_RULE_CLASS, LocalConfigPlatformRule.NAME)
                    .put(
                        ResolvedFileValue.ORIGINAL_ATTRIBUTES,
                        ImmutableMap.<String, Object>builder().put("name", name).buildOrThrow())
                    .put(ResolvedFileValue.NATIVE, repr)
                    .buildOrThrow();
              }
            });

    // Return the needed info.
    return RepositoryDirectoryValue.builder().setPath(outputDirectory);
  }

  private static String workspaceFileContent(String repositoryName) {
    return format(
        ImmutableList.of(
            "# DO NOT EDIT: automatically generated WORKSPACE file for local_config_platform",
            "workspace(name = \"%s\")"),
        repositoryName);
  }

  private static String moduleFileContent(String repositoryName) {
    return format(
        ImmutableList.of(
            "# DO NOT EDIT: automatically generated MODULE file for local_config_platform",
            "module(name = \"%s\")",
            // Try to keep this updated with the src/MODULE.tools file. (Due to MVS, even if this is
            // not kept up to date, we'll use the latest version anyhow)
            "bazel_dep(name = \"platforms\", version = \"0.0.7\")"),
        repositoryName);
  }

  private static String buildFileContent() {
    return format(
        ImmutableList.of(
            "# DO NOT EDIT: automatically generated BUILD file for local_config_platform",
            "package(default_visibility = ['//visibility:public'])",
            "alias(name = 'host', actual = '@platforms//host')",
            "exports_files([",
            "  # Export constraints.bzl for use in downstream bzl_library targets.",
            "  'constraints.bzl',",
            "])"));
  }

  private static String constraintFileContent() {
    return format(
        ImmutableList.of(
            "# DO NOT EDIT: automatically generated constraints list for local_config_platform",
            "load('@platforms//host:constraints.bzl', _HOST_CONSTRAINTS='HOST_CONSTRAINTS')",
            "HOST_CONSTRAINTS = _HOST_CONSTRAINTS"));
  }

  private static String format(List<String> lines, Object... params) {
    // Add a newline between each line, and also after the final line.
    String content = lines.stream().collect(Collectors.joining("\n", "", "\n"));
    return String.format(content, params);
  }
}
