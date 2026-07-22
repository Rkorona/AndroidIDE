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

package com.itsaky.androidide.services.builder

import ch.qos.logback.core.CoreConstants
import com.itsaky.androidide.shell.executeProcessAsync
import com.itsaky.androidide.tasks.cancelIfActive
import com.itsaky.androidide.tasks.ifCancelledOrInterrupted
import com.itsaky.androidide.tooling.api.IProject
import com.itsaky.androidide.tooling.api.IToolingApiClient
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.util.ToolingApiLauncher
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runner thread for the Tooling API.
 *
 * @author Akash Yadav
 */
internal class ToolingServerRunner(
  private var listener: OnServerStartListener?,
  private var observer: Observer?,
) {

  internal var pid: Int? = null
  private var _job: Job? = null
  private var _isStarted = AtomicBoolean(false)

  var isStarted: Boolean
    get() = _isStarted.get()
    private set(value) {
      _isStarted.set(value)
    }

  private val runnerScope = CoroutineScope(Dispatchers.IO + CoroutineName("ToolingServerRunner"))

  companion object {

    private val log = LoggerFactory.getLogger(ToolingServerRunner::class.java)
  }

  fun setListener(listener: OnServerStartListener?) {
    this.listener = listener
  }

  fun startAsync(envs: Map<String, String>) = runnerScope.launch {
    var process: Process?
    try {
      log.info("Starting tooling API server...")
      val javaPath = Environment.JAVA.absolutePath
      val command = buildList {
        // Android 16 (API 36): W^X + SELinux policy blocks execve() on app-private
        // binaries. Route through /system/bin/linker64 (always trusted) so the
        // dynamic linker mmap-loads the JVM binary instead of the kernel exec-ing it.
        if (android.os.Build.VERSION.SDK_INT >= 36) {
          add("/system/bin/linker64")
        }
        add(javaPath)
        // Allow reflective access to private members of classes in the following
        // packages:
        // - java.lang
        // - java.io
        // - java.util
        //
        // If any of the model classes in 'tooling-api-model' module send/receive
        // objects from the JDK, their package name must be declared here with
        // '--add-opens' to prevent InaccessibleObjectException.
        // For example, some of the model classes has members of type java.io.File.
        // When sending/receiving these type of objects using LSP4J, members of
        // these objects are reflectively accessed by Gson. If we do no specify
        // '--add-opens' for 'java.io' (for java.io.File) package, JVM will throw an
        // InaccessibleObjectException.
        add("--add-opens"); add("java.base/java.lang=ALL-UNNAMED")
        add("--add-opens"); add("java.base/java.util=ALL-UNNAMED")
        add("--add-opens"); add("java.base/java.io=ALL-UNNAMED")
        add("-D${CoreConstants.STATUS_LISTENER_CLASS_KEY}=com.itsaky.androidide.tooling.impl.util.LogbackStatusListener")
        add("-jar"); add(Environment.TOOLING_API_JAR.absolutePath)
      }

      process = executeProcessAsync {
        this.command = command

        // input and output is used for communication to the tooling server
        // error stream is used to read the server logs
        this.redirectErrorStream = false
        this.workingDirectory = null // HOME
        this.environment = envs
      }

      // Process.pid() is Java 9+ / API 26+ (always present since minSdk=36).
      // Called via reflection because some SDK stubs omit it from java.lang.Process,
      // causing an "Unresolved reference" compile error despite being available at runtime.
      // Cast through Number to handle both Long (standard JVM) and Integer (some Android impls).
      @Suppress("DiscouragedPrivateApi")
      pid = runCatching {
        (Process::class.java.getMethod("pid").invoke(process) as Number).toInt()
      }.getOrNull()

      val inputStream = process.inputStream
      val outputStream = process.outputStream
      val errorStream = process.errorStream

      val processJob = launch(Dispatchers.IO) {
        try {
          process?.waitFor()
          log.info("Tooling API process exited with code : {}", process?.exitValue() ?: "<unknown>")
          process = null
        } finally {
          log.info("Destroying Tooling API process...")
          process?.destroyForcibly()
        }
      }

      val launcher = ToolingApiLauncher.newClientLauncher(
        observer!!.getClient(),
        inputStream,
        outputStream
      )

      val future = launcher.startListening()
      observer?.onListenerStarted(
        server = launcher.remoteProxy as IToolingApiServer,
        projectProxy = launcher.remoteProxy as IProject,
        errorStream = errorStream
      )

      isStarted = true

      // Use -1 as sentinel if PID could not be determined via reflection
      listener?.onServerStarted(pid ?: -1)

      // we don't need the listener anymore
      // also, this might be a reference to the activity
      // release to prevent memory leak
      listener = null

      // Wait(block) until the process terminates
      val serverJob = launch(Dispatchers.IO) {
        try {
          future.get()
        } catch (err: Throwable) {
          err.ifCancelledOrInterrupted {
            log.info("ToolingServerThread has been cancelled or interrupted.")
          }

          // rethrow the error
          throw err
        }
      }

      processJob.join()
      joinAll(serverJob, processJob)
    } catch (e: Throwable) {
      if (e !is CancellationException) {
        log.error("Unable to start tooling API server", e)
      }
    }
  }.also {
    _job = it
  }

  fun release() {
    this.listener = null
    this.observer = null
    this._job?.cancel(CancellationException("Cancellation was requested"))
    this.runnerScope.cancelIfActive("Cancellation was requested")
  }

  interface Observer {

    fun onListenerStarted(
      server: IToolingApiServer,
      projectProxy: IProject,
      errorStream: InputStream,
    )

    fun onServerExited(exitCode: Int)

    fun getClient(): IToolingApiClient
  }

  /** Callback to listen for Tooling API server start event.  */
  fun interface OnServerStartListener {

    /** Called when the tooling API server has been successfully started.  */
    fun onServerStarted(pid: Int)
  }
}
