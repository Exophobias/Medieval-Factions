package com.dansplugins.factionsystem.api

/**
 * Outcome of a mutating [MedievalFactionsApi] call that produces a value, such as the id of a faction
 * it just created.
 *
 * ## Why this is not `ApiResult<T>`
 *
 * Because making [ApiResult] generic is a source break for every consumer that already names it.
 * Kotlin has no raw types, so an existing `val result: ApiResult` stops compiling the moment the
 * class grows a type parameter, and this API's entire reason for existing is that MF's internals can
 * move without consumers being recompiled. A second type is the smaller cost.
 *
 * The two are deliberately not related by inheritance either. A caller who does not care about the
 * value should be handed an [ApiResult]; one who does should be unable to ignore it by assigning the
 * outcome to the plainer type and never asking.
 *
 * ## A successful outcome always carries a value
 *
 * [success] does not accept null. "Succeeded, and here is nothing" is [ApiResult]'s job, and letting
 * it be expressed here as well would give callers two spellings of the same state to test for.
 */
class ApiOutcome<T : Any> private constructor(
    private val value: T?,
    val errorMessage: String?
) {

    val isSuccess: Boolean get() = value != null
    val isFailure: Boolean get() = !isSuccess

    /**
     * The value, or throw if this outcome is a failure.
     *
     * Throws rather than returning null so that a caller who has already tested [isSuccess] does not
     * have to test again, and one who has not finds out immediately rather than several frames later.
     * Use [orNull] where a failure is an ordinary branch.
     */
    fun get(): T = value ?: throw IllegalStateException(errorMessage ?: "Outcome was a failure")

    /** The value, or null if this outcome is a failure. */
    fun orNull(): T? = value

    /** Discard the value, keeping only whether it worked and why not. */
    fun toResult(): ApiResult =
        if (isSuccess) ApiResult.success() else ApiResult.failure(errorMessage ?: "Unknown failure")

    companion object {
        @JvmStatic
        fun <T : Any> success(value: T): ApiOutcome<T> = ApiOutcome(value, null)

        @JvmStatic
        fun <T : Any> failure(message: String): ApiOutcome<T> = ApiOutcome(null, message)
    }
}
