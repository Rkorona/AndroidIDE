package com.itsaky.androidide.build.config/*
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

import org.gradle.api.JavaVersion

/**
 * Build configuration for the IDE.
 *
 * @author Akash Yadav
 */
object BuildConfig {

  /** AndroidIDE's package name. */
  const val packageName = "com.itsaky.androidide"

  /** The compile SDK version. */
  const val compileSdk = 37

  /** The minimum SDK version. */
  const val minSdk = 36

  /** The target SDK version. */
  const val targetSdk = 37

  const val ndkVersion = "29.0.14206865"

  /**
   * The build tools version to use for Android modules.
   * Must match the version installed under $ANDROID_HOME/build-tools/.
   * AGP 9.x defaults to 36.0.0 when this is not set; explicitly pinning to 37.0.0
   * prevents AGP from auto-downloading build-tools 36 when only 37 is installed.
   */
  const val buildToolsVersion = "37.0.0"

  /** The source and target Java compatibility. */
  val javaVersion = JavaVersion.VERSION_21
}
