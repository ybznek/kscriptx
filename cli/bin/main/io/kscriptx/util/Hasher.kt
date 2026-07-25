package io.kscriptx.util

import java.security.MessageDigest

object Hasher {
    @OptIn(ExperimentalStdlibApi::class)
    fun md5(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        for (p in parts) digest.update(p)
        return digest.digest().toHexString()
    }

    fun md5(text: String): String = md5(text.toByteArray(Charsets.UTF_8))
}
