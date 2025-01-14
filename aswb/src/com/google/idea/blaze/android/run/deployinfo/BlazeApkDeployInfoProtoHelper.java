/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.run.deployinfo;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.rules.android.deployinfo.AndroidDeployInfoOuterClass;
import com.google.devtools.build.lib.rules.android.deployinfo.AndroidDeployInfoOuterClass.AndroidDeployInfo;
import com.google.devtools.build.lib.rules.android.deployinfo.AndroidDeployInfoOuterClass.Artifact;
import com.google.idea.blaze.android.manifest.ManifestParser.ParsedManifest;
import com.google.idea.blaze.android.manifest.ParsedManifestService;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper.GetArtifactsException;
import com.google.idea.blaze.base.model.primitives.Label;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Reads the deploy info from a build step. */
public class BlazeApkDeployInfoProtoHelper {
  public static AndroidDeployInfo readDeployInfoProtoForTarget(
      Label target, BuildResultHelper buildResultHelper, Predicate<String> pathFilter)
      throws GetArtifactsException {
    ImmutableList<File> artifacts =
        BlazeArtifact.getLocalFiles(
            buildResultHelper.getBuildArtifactsForTarget(target, pathFilter));
    if (artifacts.isEmpty()) {
      throw new GetArtifactsException(
          "No deploy info proto artifact found.  Was android_deploy_info in the output groups?");
    }
    if (artifacts.size() != 1) {
      String errMsg =
          "More than one deploy info proto artifact found: "
              + artifacts.stream().map(File::getPath).collect(Collectors.joining(", ", "[", "]"));
      throw new GetArtifactsException(errMsg);
    }
    File deployInfoFile = Iterables.getOnlyElement(artifacts, null);
    if (deployInfoFile == null) {
      throw new GetArtifactsException("Deploy info file doesn't exist.");
    }
    AndroidDeployInfo deployInfo;
    try (InputStream inputStream = new FileInputStream(deployInfoFile)) {
      deployInfo = AndroidDeployInfoOuterClass.AndroidDeployInfo.parseFrom(inputStream);
    } catch (IOException e) {
      throw new GetArtifactsException(e.getMessage());
    }
    return deployInfo;
  }

  public static BlazeAndroidDeployInfo extractDeployInfoAndInvalidateManifests(
      Project project, File executionRoot, AndroidDeployInfo deployInfoProto)
      throws GetArtifactsException {
    File mergedManifestFile =
        new File(executionRoot, deployInfoProto.getMergedManifest().getExecRootPath());
    ParsedManifest mergedManifest = getParsedManifestSafe(project, mergedManifestFile);
    ParsedManifestService.getInstance(project).invalidateCachedManifest(mergedManifestFile);

    // android_test targets uses additional merged manifests field of the deploy info proto to hold
    // the manifest of the test target APK.
    ParsedManifest testTargetMergedManifest = null;
    List<Artifact> additionalManifests = deployInfoProto.getAdditionalMergedManifestsList();
    if (additionalManifests.size() == 1) {
      File testTargetMergedManifestFile =
          new File(executionRoot, additionalManifests.get(0).getExecRootPath());
      testTargetMergedManifest = getParsedManifestSafe(project, testTargetMergedManifestFile);
      ParsedManifestService.getInstance(project)
          .invalidateCachedManifest(testTargetMergedManifestFile);
    }

    ImmutableList<File> apksToDeploy =
        deployInfoProto.getApksToDeployList().stream()
            .map(artifact -> new File(executionRoot, artifact.getExecRootPath()))
            .collect(ImmutableList.toImmutableList());

    return new BlazeAndroidDeployInfo(mergedManifest, testTargetMergedManifest, apksToDeploy);
  }

  /** Transforms thrown {@link IOException} to {@link GetArtifactsException} */
  private static ParsedManifest getParsedManifestSafe(Project project, File manifestFile)
      throws GetArtifactsException {
    try {
      return ParsedManifestService.getInstance(project).getParsedManifest(manifestFile);
    } catch (IOException e) {
      throw new GetArtifactsException(
          "Could not read merged manifest file "
              + manifestFile
              + " due to error: "
              + e.getMessage());
    }
  }
}
