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

package com.itsaky.androidide.templates

/**
 * @author Akash Yadav
 */

// AGP 8.6.x requires Gradle 8.7+; Kotlin 2.0.x is stable with these versions.
// Gradle must be >= 8.4: that's when ASM was upgraded to support Java 21 class files
// (major version 65). Older Gradle versions throw "Unsupported class file major version 65"
// when the tooling server JAR (compiled to Java 21) is loaded during project initialization.
const val ANDROID_GRADLE_PLUGIN_VERSION = "8.6.1"
const val GRADLE_DISTRIBUTION_VERSION = "8.9"
const val KOTLIN_VERSION = "2.0.21"

val TARGET_SDK_VERSION = Sdk.Tiramisu
val COMPILE_SDK_VERSION = Sdk.Tiramisu

const val JAVA_SOURCE_VERSION = "11"
const val JAVA_TARGET_VERSION = "11"
