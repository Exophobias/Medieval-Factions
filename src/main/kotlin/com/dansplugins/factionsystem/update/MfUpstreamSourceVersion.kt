package com.dansplugins.factionsystem.update

/** The upstream source release, independent of retained consumer Maven coordinates. */
object MfUpstreamSourceVersion {
    fun current(): String = java.util.Properties().apply {
        MfUpstreamSourceVersion::class.java.getResourceAsStream("/patriam-upstream-version.properties")
            ?.use { load(it) }
    }.getProperty("version") ?: "unknown"
}
