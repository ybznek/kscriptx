package io.kscriptx.compile

import io.kscriptx.KscriptVersions
import io.kscriptx.VERSION
import kotlin.test.Test
import kotlin.test.assertTrue

class FastCacheTest {
    @Test
    fun configStampIncludesCliAndKotlinVersions() {
        val stamp = FastCache.configStamp()
        assertTrue(
            stamp.startsWith("$VERSION:${KscriptVersions.KOTLIN}:"),
            "expected version prefix in stamp, got: $stamp",
        )
    }
}
