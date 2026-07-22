/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.build.config

import org.gradle.api.Project

/** @author Akash Yadav */
object ProjectConfig {

  const val REPO_HOST = "github.com"
  const val REPO_OWNER = "AndroidIDEOfficial"
  const val REPO_NAME = "AndroidIDE"
  const val REPO_URL = "https://$REPO_HOST/$REPO_OWNER/$REPO_NAME"
  const val SCM_GIT =
    "scm:git:git://$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"
  const val SCM_SSH =
    "scm:git:ssh://git@$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"

  const val PROJECT_SITE = "https://m.androidide.com"
}

private var shouldPrintNotAGitRepoWarning = true
private var shouldPrintVersionName = true

/**
 * Whether this build is being executed in the F-Droid build server.
 */
val Project.isFDroidBuild: Boolean
  get() {
    if (!FDroidConfig.hasRead) {
      FDroidConfig.load(this)
    }
    return com.itsaky.androidide.build.config.FDroidConfig.isFDroidBuild
  }

val Project.simpleVersionName: String
  get() {

    if (!CI.isGitRepo) {
      if (shouldPrintNotAGitRepoWarning) {
        logger.warn("Unable to infer version name. The build is not running on a git repository.")
        shouldPrintNotAGitRepoWarning = false
      }

      return "1.0.0-beta"
    }

    val version = rootProject.version.toString()
    val regex = Regex("^v\\d+\\.?\\d+\\.?\\d+-\\w+")

    val simpleVersion = regex.find(version)?.value?.substring(1)?.also {
      if (shouldPrintVersionName) {
        logger.warn("Simple version name is '$it' (from version $version)")
        shouldPrintVersionName = false
      }
    }

    if (simpleVersion == null) {
      if (CI.isTestEnv) {
        return "1.0.0-beta"
      }

      throw IllegalStateException(
        "Cannot extract simple version name. Invalid version string '$version'. Version names must be SEMVER with 'v' prefix"
      )
    }

    return simpleVersion
  }

private var shouldPrintVersionCode = true
val Project.projectVersionCode: Int
  get() {

    val version = simpleVersionName
    val regex = Regex("^\\d+\\.?\\d+\\.?\\d+")

    val versionCode = regex.find(version)?.value?.replace(".", "")?.toInt()?.also {
      if (shouldPrintVersionCode) {
        logger.warn("Version code is '$it' (from version ${version}).")
        shouldPrintVersionCode = false
      }
    }
      ?: throw IllegalStateException(
        "Cannot extract version code. Invalid version string '$version'. Version names must be SEMVER with 'v' prefix"
      )

    return versionCode
  }

val Project.publishingVersion: String
  get() {

    var publishing = simpleVersionName
    if (isFDroidBuild) {
      // when building for F-Droid, the release is already published so we should have
      // the maven dependencies already published
      // simply return the simple version name here.
      return publishing
    }

    if (CI.isCiBuild && CI.isGitRepo && CI.branchName != "main") {
      publishing += "-${CI.commitHash}-SNAPSHOT"
    }

    return publishing
  }

/**
 * The version name which is used to download the artifacts at runtime.
 *
 * The value varies based on the following cases:
 * - For F-Droid builds: the exact [publishingVersion] (always fully published).
 * - For release (non-SNAPSHOT) CI builds on the main branch: the exact [publishingVersion]
 *   (published to Sonatype during the CI release pipeline).
 * - For SNAPSHOT builds (local dev and non-main CI branches): queries Sonatype for the latest
 *   actually-published snapshot. Non-main CI builds embed a commit-hash SNAPSHOT version that
 *   is never uploaded to Sonatype, so using it directly causes "Could not find artifact" errors
 *   in every user project initialized by that build of the IDE.
 */
val Project.downloadVersion: String
  get() {
    // F-Droid releases are always published with the exact version.
    if (isFDroidBuild) return publishingVersion

    // Main-branch CI (and stable) builds publish the artifact with its exact version.
    if (!publishingVersion.endsWith("-SNAPSHOT")) return publishingVersion

    // For SNAPSHOT builds (local and non-main CI), resolve the latest version that actually
    // exists in Sonatype rather than the exact commit-hash SNAPSHOT which is rarely published.
    return VersionUtils.getLatestSnapshotVersion("gradle-plugin")
  }
