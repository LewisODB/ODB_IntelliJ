package org.lewisodb.intellij.lifecycle

import com.intellij.openapi.application.PathManager
import java.nio.file.Path

object OdbSessionPaths {
    fun managedRoot(): Path = PathManager.getSystemDir().resolve("lewis-odb").resolve("sessions")
}
