package com.dansplugins.factionsystem.failure

/**
 * Thrown when a JSON storage file cannot be read or written because its contents are unreadable.
 *
 * The JSON backend mutates a file by loading it, changing the loaded copy and writing the whole thing
 * back. If the load could not read the stored contents, the copy being written no longer represents
 * them, and writing it would replace real records with nothing. Reads and writes are refused until
 * the original file is repaired or removed. No backup is created automatically.
 */
class UnreadableJsonFileException(val fileName: String, cause: Throwable? = null) : Exception(
    "Cannot use $fileName because its stored contents could not be read. " +
        "The original file is unchanged; repair or remove it to resume reads and writes.",
    cause
)
