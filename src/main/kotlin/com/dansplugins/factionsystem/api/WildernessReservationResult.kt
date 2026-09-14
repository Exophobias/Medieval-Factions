package com.dansplugins.factionsystem.api

import java.util.UUID

/** Only ACQUIRED carries a token. No rejected result reserves any part of the requested area. */
data class WildernessReservationResult(val status: WildernessReservationStatus, val token: UUID? = null)
