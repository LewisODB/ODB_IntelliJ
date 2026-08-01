package org.lewisodb.intellij.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OdbRuntimeManifestTest {
    @Test
    fun `accepts the release provenance schema`() {
        val manifest = OdbRuntimeManifest.parse(validManifest())

        assertEquals(COMMIT, manifest.sourceCommit)
        assertEquals(DIGEST, manifest.sha256)
        assertEquals("odb-source-$COMMIT.tar.gz", manifest.sourceArtifact)
        assertEquals(52, manifest.javaClassVersion)
        assertEquals(1, manifest.integrationProtocol)
        assertEquals("com.lambda.Debugger.IntegrationLauncher", manifest.adapterClass)
        assertEquals(4, manifest.dependencies.size)
    }

    @Test
    fun `rejects missing extra or invalid fields`() {
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(validManifest().replace(",\"integrationProtocol\":1", ""))
        }
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(validManifest().replace("}", ",\"extra\":true}"))
        }
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(validManifest().replace("\"javaClassVersion\":52", "\"javaClassVersion\":61"))
        }
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(validManifest().replace("\"javaClassVersion\":52", "\"javaClassVersion\":52.0"))
        }
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(
                validManifest().replace("\"sourceCommit\":\"$COMMIT\"", "\"sourceCommit\":\"${"b".repeat(40)}\""),
            )
        }
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(validManifest().replace("asm:9.7.1", "asm:9.7.2"))
        }
    }

    companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"

        fun validManifest(sha256: String = DIGEST): String =
            """{"sourceCommit":"$COMMIT","artifact":"odb-runtime.jar","sha256":"$sha256","sourceArtifact":"odb-source-$COMMIT.tar.gz","sourceSha256":"$DIGEST","javaClassVersion":52,"integrationProtocol":1,"adapterClass":"com.lambda.Debugger.IntegrationLauncher","dependencies":["commons-io:commons-io:2.21.0","org.apache.bcel:bcel:6.12.0","org.apache.commons:commons-lang3:3.20.0","org.ow2.asm:asm:9.7.1"]}"""
    }
}
