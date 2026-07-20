package org.lewisodb.intellij.lifecycle

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

sealed interface OdbCleanupResult {
    data object Cleaned : OdbCleanupResult
    data class Failed(val directory: Path, val cause: Exception) : OdbCleanupResult
}

class OdbSessionState private constructor(
    val directory: Path,
    private val deleteDirectory: (Path) -> Unit,
) {
    private var result: OdbCleanupResult? = null

    @Synchronized
    fun cleanup(): OdbCleanupResult {
        result?.let { return it }
        return try {
            deleteDirectory(directory)
            OdbCleanupResult.Cleaned
        } catch (error: Exception) {
            OdbCleanupResult.Failed(directory, error)
        }.also { result = it }
    }

    companion object {
        fun create(managedRoot: Path, directory: Path): OdbSessionState =
            create(managedRoot, directory, ::deleteTree)

        internal fun create(
            managedRoot: Path,
            directory: Path,
            deleteDirectory: (Path) -> Unit,
        ): OdbSessionState {
            val root = managedRoot.toRealPath()
            val session = directory.toRealPath()
            require(session.parent == root) {
                "ODB session directory must be a direct child of plugin-managed state."
            }
            return OdbSessionState(session, deleteDirectory)
        }

        internal fun deleteTree(directory: Path) {
            if (Files.notExists(directory)) return
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
