package org.lewisodb.intellij.lifecycle

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

data class OdbProcessIdentity(
    val pid: Long,
    val startedAt: Instant,
)

sealed interface OdbProcessObservation {
    data object Missing : OdbProcessObservation
    data object Unknown : OdbProcessObservation
    data class Running(val identity: OdbProcessIdentity) : OdbProcessObservation
}

internal enum class OdbRecordedIdentityStatus {
    STALE,
    LIVE,
    REUSED,
    UNKNOWN,
}

internal fun classifyRecordedIdentity(
    recorded: OdbProcessIdentity,
    observed: OdbProcessObservation,
): OdbRecordedIdentityStatus = when (observed) {
    OdbProcessObservation.Missing -> OdbRecordedIdentityStatus.STALE
    OdbProcessObservation.Unknown -> OdbRecordedIdentityStatus.UNKNOWN
    is OdbProcessObservation.Running -> {
        if (observed.identity == recorded) OdbRecordedIdentityStatus.LIVE else OdbRecordedIdentityStatus.REUSED
    }
}

fun interface OdbProcessInspector {
    fun inspect(pid: Long): OdbProcessObservation
}

object JvmOdbProcessInspector : OdbProcessInspector {
    override fun inspect(pid: Long): OdbProcessObservation = try {
        val process = ProcessHandle.of(pid)
        if (process.isEmpty) {
            OdbProcessObservation.Missing
        } else {
            val startedAt = process.get().info().startInstant()
            if (startedAt.isPresent) {
                OdbProcessObservation.Running(OdbProcessIdentity(pid, startedAt.get()))
            } else {
                OdbProcessObservation.Unknown
            }
        }
    } catch (_: SecurityException) {
        OdbProcessObservation.Unknown
    }

    fun identify(process: ProcessHandle): OdbProcessIdentity? =
        process.info().startInstant().orElse(null)?.let { OdbProcessIdentity(process.pid(), it) }
}

data class OdbSweepReport(
    val deleted: Int,
    val preserved: Int,
    val failed: Int,
)

class OdbStaleSessionSweeper(
    private val managedRoot: Path,
    private val processInspector: OdbProcessInspector = JvmOdbProcessInspector,
) {
    fun sweep(): OdbSweepReport {
        if (Files.notExists(managedRoot)) return OdbSweepReport(0, 0, 0)
        val root = try {
            if (Files.isSymbolicLink(managedRoot)) return OdbSweepReport(0, 1, 0)
            managedRoot.toRealPath()
        } catch (_: Exception) {
            return OdbSweepReport(0, 0, 1)
        }
        var report = OdbSweepReport(0, 0, 0)
        try {
            Files.list(root).use { entries ->
                entries.forEach { directory ->
                    report += inspect(root, directory)
                }
            }
        } catch (_: Exception) {
            report = report.copy(failed = report.failed + 1)
        }
        return report
    }

    private fun inspect(root: Path, directory: Path): OdbSweepReport {
        if (
            Files.isSymbolicLink(directory) ||
            !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        ) {
            return if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) preserved() else empty()
        }
        val metadata = try {
            OdbSessionMetadata.read(directory)
        } catch (_: NoSuchFileException) {
            return empty()
        } catch (_: Exception) {
            return preserved()
        } ?: return preserved()
        if (directory.fileName.toString() != "session-${metadata.sessionId}") return preserved()
        if (!isConcludedStale(metadata.ideOwner) || !isConcludedStale(metadata.odbChild)) return preserved()

        return try {
            when (OdbSessionState.create(root, directory).cleanup()) {
                OdbCleanupResult.Cleaned -> OdbSweepReport(1, 0, 0)
                is OdbCleanupResult.Failed -> OdbSweepReport(0, 0, 1)
            }
        } catch (_: NoSuchFileException) {
            empty()
        } catch (_: Exception) {
            OdbSweepReport(0, 0, 1)
        }
    }

    private fun isConcludedStale(identity: OdbProcessIdentity?): Boolean {
        if (identity == null) return false
        return classifyRecordedIdentity(identity, processInspector.inspect(identity.pid)) ==
            OdbRecordedIdentityStatus.STALE
    }

    private fun empty() = OdbSweepReport(0, 0, 0)

    private fun preserved() = OdbSweepReport(0, 1, 0)

    private operator fun OdbSweepReport.plus(other: OdbSweepReport) = OdbSweepReport(
        deleted + other.deleted,
        preserved + other.preserved,
        failed + other.failed,
    )
}
