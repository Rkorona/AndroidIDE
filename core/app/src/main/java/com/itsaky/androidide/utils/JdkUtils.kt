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

package com.itsaky.androidide.utils

import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.models.JdkDistribution
import com.itsaky.androidide.shell.executeProcessAsync
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Utilities related to JDK installations.
 *
 * @author Akash Yadav
 */
object JdkUtils {

  private val log = LoggerFactory.getLogger(JdkUtils::class.java)

  /**
   * Finds the available JDK installations and returns the JAVA_HOME for each installation.
   */
  @JvmStatic
  @WorkerThread
  fun findJavaInstallations(): List<JdkDistribution> {

    // a valid JDK can be installed anywhere in the file system
    // however, we currently only check for installations that are located in $PREFIX/opt dir
    // TODO: Find a way to efficiently list all JDK installations, including those which are located
    //    outside of $PREFIX/opt
    return try {
      val optDir = File(Environment.PREFIX, "opt")
      if (!optDir.exists() || !optDir.isDirectory) {
        emptyList()
      } else {
        optDir.listFiles()?.mapNotNull { dir ->
          if (Files.isSymbolicLink(dir.toPath())) {
            // ignore symbolic links
            return@mapNotNull null
          }

          val java = File(dir, "bin/java")
          if (!java.exists() || !java.isFile) {
            // java binary does not exist
            return@mapNotNull null
          }

          // On Android 16+, W^X + SELinux policy blocks execve() on files in the app's
          // private data directory, so executing the java binary to read its properties
          // throws IOException (EACCES). Read the 'release' file instead — it contains
          // JAVA_VERSION without requiring any process execution.
          return@mapNotNull getDistFromReleaseFile(dir)
            ?: run {
              // Fallback: try executing the binary (works on Android < 16)
              if (canExecute(java)) getDistFromJavaBin(java) else null
            }
        } ?: run {
          log.error("Failed to list files in {}", optDir)
          emptyList()
        }
      }
    } catch (e: Exception) {
      log.error("Failed to list java alternatives", e)
      emptyList()
    }
  }

  /**
   * Reads JDK distribution info from the standard `release` file in the given JDK installation
   * directory. This avoids executing the java binary, which fails on Android 16+ due to the
   * W^X (Write XOR Execute) + SELinux policy that blocks execve() on app private data files.
   *
   * The `release` file is a standard part of every JDK distribution and contains lines such as:
   * ```
   * JAVA_VERSION="17.0.8"
   * ```
   *
   * @param jdkDir The root directory of the JDK installation (i.e. the value of JAVA_HOME).
   * @return A [JdkDistribution] instance, or `null` if the file is missing or unparseable.
   */
  @JvmStatic
  fun getDistFromReleaseFile(jdkDir: File): JdkDistribution? {
    val releaseFile = File(jdkDir, "release")
    if (!releaseFile.exists() || !releaseFile.isFile) {
      log.debug("No 'release' file found in {}", jdkDir)
      return null
    }
    return try {
      val content = releaseFile.readText()
      // JAVA_VERSION may be quoted ("17.0.8") or unquoted (17.0.8)
      val javaVersion =
        Regex("""JAVA_VERSION\s*=\s*"?([^"\n\r]+)"?""").find(content)?.groupValues?.get(1)
          ?.trim()
          ?: run {
            log.warn("JAVA_VERSION not found in release file: {}", releaseFile)
            return null
          }
      log.debug("Read JAVA_VERSION={} from release file in {}", javaVersion, jdkDir)
      JdkDistribution(javaVersion, jdkDir.absolutePath)
    } catch (e: Exception) {
      log.warn("Failed to read release file from {}", releaseFile, e)
      null
    }
  }

  private fun canExecute(file: File): Boolean {
    return file.exists() && file.isFile && file.canExecute()
  }

  /**
   * Returns a [JdkDistribution] instances representing the JDK installation of the given
   * `java` binary executable. This binary file is executed to extract the actual `java.home`
   * value.
   *
   * @param java The path to the `java` binary executable.
   * @return The [JdkDistribution] instance, or `null` if there was an error while getting required
   * information from the installation.
   */
  @JvmStatic
  fun getDistFromJavaBin(java: File): JdkDistribution? {
    if (!java.exists() || !java.isFile || !java.canExecute()) {
      log.error(
        "Failed to lookup JDK installation. File '{}' does not exist or cannot be executed.", java)
      return null
    }

    val properties = readProperties(java) ?: run {
      log.error("Failed to retrieve Java properties from java binary: '{}'", java)
      return null
    }

    return readDistFromProps(properties)
  }

  @VisibleForTesting
  internal fun readDistFromProps(properties: String): JdkDistribution? {
    val javaHome = Regex("java\\.home\\s*=\\s*(.*)").find(properties)?.groupValues?.get(1) ?: run {
      log.error("Failed to determine property 'java.home'. Properties: {}", properties)
      return null
    }

    log.debug("Found java.home=${javaHome}")

    val javaVersion = Regex("java\\.version\\s*=\\s*(.*)").find(properties)?.groupValues?.get(1)
      ?: run {
        log.error("Failed to determine property 'java.version'. Properties: {}", properties)
        return null
      }

    log.debug("Found java.version={}", javaVersion)

    return JdkDistribution(javaVersion, javaHome)
  }

  /**
   * Returns a [JdkDistribution] instance representing the JDK installation at the given
   * location.
   *
   * @param javaHome The path to the installed JDK.
   * @return The [JdkDistribution] instance, or `null` if there was an error while getting required
   * information from the installation.
   */
  @JvmStatic
  fun getDistFromJavaHome(javaHome: File): JdkDistribution? {
    return getDistFromJavaBin(javaHome.resolve("bin/java"))
  }

  private fun readProperties(file: File): String? {
    val propsCmd = "${file.absolutePath} -XshowSettings:properties -version"
    val process = executeWithBash(propsCmd) ?: return null
    return process.inputStream.bufferedReader().readText()
  }

  @WorkerThread
  private fun executeWithBash(cmd: String): Process? {
    val shell = Environment.BASH_SHELL

    if (!canExecute(shell)) {
      log.warn(
        "Unable to determine JDK installations. Command {} not found or is not executable.",
        shell.absolutePath)
      return null
    }

    val env = HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false))

    return executeProcessAsync {
      command = listOf(shell.absolutePath, "-c", cmd)
      environment = env
      redirectErrorStream = true
      workingDirectory = Environment.HOME
    }
  }
}