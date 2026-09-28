package com.dansplugins.factionsystem.listener

import com.dansplugins.factionsystem.claim.EmbassyAccessDecision
import com.dansplugins.factionsystem.claim.MfEmbassyService
import com.dansplugins.factionsystem.service.Services
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer

/** Existing listener fixtures run without a charter; individual tests can override this answer. */
internal fun defaultEmbassyService(services: Services): MfEmbassyService {
    val embassy = mock(
        MfEmbassyService::class.java,
        Answer { call ->
        if (call.method.name.startsWith("access")) {
            EmbassyAccessDecision.NONE
        } else {
            Answers.RETURNS_DEFAULTS.answer(call)
        }
    }
    )
    `when`(services.embassyService).thenReturn(embassy)
    return embassy
}
