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


@Suppress("JavaPluginLanguageLevel")
plugins {
  id("java-library")
  id("org.jetbrains.kotlin.jvm")
}



dependencies {
  api(libs.tooling.builderModel)

  implementation(libs.common.jkotlin)

  implementation(projects.utilities.buildInfo)
}

// The tooling server JAR is executed by AndroidIDE's bundled JDK 17.
// Override compile tasks to target Java 17 without changing Gradle's
// org.gradle.jvm.version variant attribute (which would break dependency resolution).
afterEvaluate {
  tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
  }
  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }
}
