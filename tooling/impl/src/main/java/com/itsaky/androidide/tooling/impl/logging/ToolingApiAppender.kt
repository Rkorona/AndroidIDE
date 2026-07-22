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

package com.itsaky.androidide.tooling.impl.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import com.itsaky.androidide.tooling.api.messages.LogMessageParams
import com.itsaky.androidide.tooling.impl.Main

/**
 * [AppenderBase] implementation which forwards all logs to the tooling API client.
 *
 * @author Akash Yadav
 */
class ToolingApiAppender : AppenderBase<ILoggingEvent>() {

  override fun append(eventObject: ILoggingEvent?) {
    if (eventObject == null || !isStarted) {
      return
    }

    // Include exception stacktrace when present so callers can diagnose failures.
    // eventObject.formattedMessage contains only the message text; the Throwable is
    // stored separately in throwableProxy and must be appended explicitly.
    val message = buildString {
      append(eventObject.formattedMessage)
      eventObject.throwableProxy?.let { proxy ->
        append('\n')
        append(ThrowableProxyUtil.asString(proxy))
      }
    }

    Main.client?.logMessage(
      LogMessageParams(
        eventObject.level.levelStr[0],
        eventObject.loggerName,
        message
      )
    )
  }
}