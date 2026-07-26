package org.lewisodb.intellij.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OdbRuntimeManifestTest {
    @Test
    fun `accepts the minimal runtime schema`() {
        val manifest = OdbRuntimeManifest.parse(validManifest())

        assertEquals(DIGEST, manifest.sha256)
        assertEquals(52, manifest.javaClassVersion)
        assertEquals(1, manifest.integrationProtocol)
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
    }

    companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun validManifest(sha256: String = DIGEST): String =
            """{"artifact":"odb-runtime.jar","sha256":"$sha256","javaClassVersion":52,"integrationProtocol":1}"""
    }
}
