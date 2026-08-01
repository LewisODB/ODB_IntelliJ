package org.lewisodb.intellij.lifecycle

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID

sealed interface OdbCleanupResult {
    data object Cleaned : OdbCleanupResult
    data class Failed(val directory: Path, val cause: Exception) : OdbCleanupResult
}

class OdbSessionState private constructor(
    val directory: Path,
    private val deleteDirectory: (Path) -> Unit,
    private var metadata: OdbSessionMetadata? = null,
    private val metadataFile: OdbSessionMetadataFile? = null,
) {
    private var result: OdbCleanupResult? = null
    internal val recordsProcessIdentity: Boolean get() = metadata != null

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

    @Synchronized
    internal fun recordChild(identity: OdbProcessIdentity) {
        if (result != null) return
        val current = checkNotNull(metadata) { "ODB session metadata is unavailable." }
        if (current.odbChild == identity) return
        check(current.odbChild == null) { "ODB child identity is already recorded." }
        val updated = current.copy(odbChild = identity)
        checkNotNull(metadataFile).write(updated)
        metadata = updated
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

        internal fun createManaged(
            managedRoot: Path,
            ownerIdentity: OdbProcessIdentity,
            sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
            deleteDirectory: (Path) -> Unit = ::deleteTree,
            metadataMover: OdbMetadataMover = OdbMetadataMover { source, target ->
                Files.move(
                    source,
                    target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            },
        ): OdbSessionState {
            val root = managedRoot.toRealPath()
            val sessionId = OdbSessionMetadata.requireSessionId(sessionIdFactory())
            val directory = Files.createDirectory(root.resolve("session-$sessionId")).toRealPath()
            val metadataFile = OdbSessionMetadataFile(directory, metadataMover)
            val metadata = OdbSessionMetadata(sessionId, ownerIdentity, null)
            val state = OdbSessionState(directory, deleteDirectory, metadata, metadataFile)
            try {
                metadataFile.write(metadata)
            } catch (error: Exception) {
                val cleanup = state.cleanup()
                if (cleanup is OdbCleanupResult.Failed) error.addSuppressed(cleanup.cause)
                throw error
            }
            return state
        }

        internal fun deleteTree(directory: Path) {
            if (Files.notExists(directory)) return
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
