package com.dansplugins.factionsystem.api

/**
 * Outcome of a mutating [MedievalFactionsApi] call. Deliberately does NOT expose MedievalFactions'
 * internal `Result4k`/`ServiceFailure` types, so consumers take on no dependency on MF internals.
 */
class ApiResult private constructor(
    val isSuccess: Boolean,
    val errorMessage: String?
) {
    val isFailure: Boolean get() = !isSuccess

    companion object {
        @JvmStatic
        fun success(): ApiResult = ApiResult(true, null)

        @JvmStatic
        fun failure(message: String): ApiResult = ApiResult(false, message)
    }
}
