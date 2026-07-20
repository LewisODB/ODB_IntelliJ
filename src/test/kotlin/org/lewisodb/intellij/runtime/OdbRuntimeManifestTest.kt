package org.lewisodb.intellij.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OdbRuntimeManifestTest {
    @Test
    fun `accepts the exact pinned runtime schema`() {
        val manifest = OdbRuntimeManifest.parse(validManifest())

        assertEquals(COMMIT, manifest.sourceCommit)
        assertEquals(DIGEST, manifest.sha256)
        assertEquals(52, manifest.javaClassVersion)
        assertEquals(1, manifest.integrationProtocol)
        assertEquals(OdbRuntimeManifest.REQUIRED_DEPENDENCIES, manifest.dependencies)
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
            OdbRuntimeManifest.parse(validManifest().replace(COMMIT, "main"))
        }
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(validManifest().replace("\"6.12.0\"", "\"6.11.0\""))
        }
        assertThrows(OdbRuntimeException::class.java) {
            OdbRuntimeManifest.parse(validManifest().replace("\"javaClassVersion\":52", "\"javaClassVersion\":52.0"))
        }
    }

    companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SOURCE_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        fun validManifest(sha256: String = DIGEST): String =
            """{"sourceRepository":"https://github.com/LewisODB/OmniscientDebugger","sourceCommit":"$COMMIT","artifact":"odb-runtime.jar","sha256":"$sha256","sourceArchive":"odb-source-$COMMIT.tar.gz","sourceSha256":"$SOURCE_DIGEST","javaClassVersion":52,"integrationProtocol":1,"dependencies":{"org.apache.bcel:bcel":"6.12.0","org.apache.commons:commons-lang3":"3.20.0","commons-io:commons-io":"2.21.0","org.ow2.asm:asm":"9.7.1"}}"""
    }
}
