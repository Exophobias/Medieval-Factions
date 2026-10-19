package com.dansplugins.factionsystem.welcome

import com.dansplugins.factionsystem.MedievalFactions

/** No optional SPI class literals or descriptors cross this lifecycle boundary. */
object WelcomeFeatureIntegration {
    fun start(owner: MedievalFactions): AutoCloseable {
        val welcome = owner.server.pluginManager.getPlugin("PatriamWelcome")
        if (welcome == null || !welcome.isEnabled) return AutoCloseable { }
        return try {
            val loader = WelcomeFeatureIntegration::class.java.classLoader
            listOf("HubFeatureProvider", "HubFeatureView", "HubFeature").forEach {
                Class.forName("com.github.exophobias.patriamwelcome.api.$it", false, loader)
            }
            Class.forName("com.dansplugins.factionsystem.welcome.WelcomeFeatureProvider", true, loader)
                .getMethod("register", MedievalFactions::class.java).invoke(null, owner) as AutoCloseable
        } catch (unavailable: ReflectiveOperationException) {
            owner.logger.warning("Welcome feature pages are unavailable; realm commands remain available.")
            AutoCloseable { }
        } catch (unavailable: LinkageError) {
            owner.logger.warning("Welcome feature pages are unavailable; realm commands remain available.")
            AutoCloseable { }
        }
    }

    fun close(owner: MedievalFactions, registration: AutoCloseable) {
        try {
            registration.close()
        } catch (unavailable: Exception) {
            owner.logger.warning("Could not withdraw Welcome realm page.")
        } catch (unavailable: LinkageError) {
            owner.logger.warning("Could not withdraw Welcome realm page.")
        }
    }
}
