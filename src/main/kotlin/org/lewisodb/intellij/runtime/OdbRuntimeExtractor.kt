package org.lewisodb.intellij.runtime

import org.lewisodb.intellij.lifecycle.OdbCleanupResult
import org.lewisodb.intellij.lifecycle.JvmOdbProcessInspector
import org.lewisodb.intellij.lifecycle.OdbProcessIdentity
import org.lewisodb.intellij.lifecycle.OdbSessionState
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.jar.JarFile

interface OdbRuntimeBundle {
    fun openManifest(): InputStream?
    fun openRuntime(): InputStream?
}

fun interface OdbAtomicMover {
    fun move(source: Path, target: Path)
}

data class OdbPreparedRuntime(
    val session: OdbSessionState,
    val runtimeJar: Path,
    val token: String,
) {
    val sessionDirectory: Path get() = session.directory
}

class OdbRuntimeExtractor(
    private val managedRoot: Path,
    private val bundle: OdbRuntimeBundle,
    private val atomicMover: OdbAtomicMover = OdbAtomicMover { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    },
    private val tokenFactory: () -> String = ::randomToken,
    private val deleteSessionDirectory: (Path) -> Unit = OdbSessionState::deleteTree,
    private val ownerIdentityFactory: () -> OdbProcessIdentity? = {
        JvmOdbProcessInspector.identify(ProcessHandle.current())
    },
    private val sessionIdFactory: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun prepare(): OdbPreparedRuntime {
        val root = prepareManagedRoot()
        val sessionState = try {
            val ownerIdentity = ownerIdentityFactory()
                ?: throw OdbRuntimeException("Could not identify the IDE owner process.")
            OdbSessionState.createManaged(
                root,
                ownerIdentity,
                sessionIdFactory,
                deleteSessionDirectory,
            )
        } catch (error: Exception) {
            if (error is OdbRuntimeException) throw error
            throw OdbRuntimeException("Could not create private ODB session state.", error)
        }
        val session = sessionState.directory

        try {
            val manifest = readManifest()
            val temporary = session.resolve(".odb-runtime.jar.copying")
            val runtime = session.resolve(manifest.artifact)
            val actualDigest = copyAndDigest(temporary)
            if (actualDigest != manifest.sha256) {
                throw OdbRuntimeException("Bundled ODB runtime digest does not match its manifest.")
            }
            requireClassVersion(temporary, manifest.javaClassVersion)
            atomicMover.move(temporary, runtime)
            if (!Files.isRegularFile(runtime)) {
                throw OdbRuntimeException("Verified ODB runtime was not exposed after atomic move.")
            }
            return OdbPreparedRuntime(sessionState, runtime, validatedToken())
        } catch (error: Exception) {
            val preparationError = when {
                error is OdbRuntimeException -> error
                error is AtomicMoveNotSupportedException ->
                    OdbRuntimeException("Atomic ODB runtime installation is unavailable.", error)
                else -> OdbRuntimeException("Could not prepare the bundled ODB runtime.", error)
            }
            val cleanup = sessionState.cleanup()
            if (cleanup is OdbCleanupResult.Failed) {
                preparationError.addSuppressed(cleanup.cause)
            }
            throw preparationError
        }
    }

    private fun readManifest(): OdbRuntimeManifest {
        try {
            val stream = bundle.openManifest()
                ?: throw OdbRuntimeException("Bundled ODB runtime manifest is missing.")
            return stream.use { OdbRuntimeManifest.parse(it.reader(Charsets.UTF_8).readText()) }
        } catch (error: OdbRuntimeException) {
            throw error
        } catch (error: Exception) {
            throw OdbRuntimeException("Could not read the bundled ODB runtime manifest.", error)
        }
    }

    private fun prepareManagedRoot(): Path {
        val normalized = managedRoot.toAbsolutePath().normalize()
        try {
            if (Files.isSymbolicLink(normalized)) {
                throw OdbRuntimeException("ODB managed-state root must not be a symbolic link.")
            }
            Files.createDirectories(normalized)
            return normalized.toRealPath()
        } catch (error: OdbRuntimeException) {
            throw error
        } catch (error: Exception) {
            throw OdbRuntimeException("Could not access plugin-managed ODB state.", error)
        }
    }

    private fun copyAndDigest(target: Path): String {
        val source = bundle.openRuntime() ?: throw OdbRuntimeException("Bundled ODB runtime is missing.")
        val digest = MessageDigest.getInstance("SHA-256")
        source.use { input ->
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun requireClassVersion(jar: Path, expected: Int) {
        val bytes = try {
            JarFile(jar.toFile()).use { archive ->
                val entry = archive.getJarEntry("com/lambda/Debugger/IntegrationLauncher.class")
                    ?: throw OdbRuntimeException("Bundled ODB runtime lacks its integration launcher.")
                archive.getInputStream(entry).use { it.readNBytes(8) }
            }
        } catch (error: OdbRuntimeException) {
            throw error
        } catch (error: Exception) {
            throw OdbRuntimeException("Bundled ODB runtime is not a readable JAR.", error)
        }
        if (bytes.size != 8 || bytes[0] != 0xca.toByte() || bytes[1] != 0xfe.toByte() ||
            bytes[2] != 0xba.toByte() || bytes[3] != 0xbe.toByte()
        ) {
            throw OdbRuntimeException("Bundled ODB launcher is not a Java class.")
        }
        val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        if (major != expected) {
            throw OdbRuntimeException("Bundled ODB launcher has Java class version $major, expected $expected.")
        }
    }

    private fun validatedToken(): String = tokenFactory().also {
        if (!it.matches(Regex("[0-9a-f]{32}"))) {
            throw OdbRuntimeException("Generated ODB session token is invalid.")
        }
    }

    private companion object {
        fun randomToken(): String = ByteArray(16).also(SecureRandom()::nextBytes).toHex()

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
