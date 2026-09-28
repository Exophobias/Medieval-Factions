package com.dansplugins.factionsystem.storage.json

import com.dansplugins.factionsystem.claim.MfEmbassy
import com.dansplugins.factionsystem.claim.MfEmbassyRepository
import com.dansplugins.factionsystem.claim.MfEmbassyStatus
import com.dansplugins.factionsystem.claim.validateStoredEmbassy
import com.dansplugins.factionsystem.faction.MfFactionId
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.math.BigDecimal
import java.util.UUID

/** JSON counterpart of the embassy table, with one atomic write for every area transition. */
class JsonMfEmbassyRepository(private val storageManager: JsonStorageManager) : MfEmbassyRepository {
    private val fileName = "embassies.json"
    private val writerGson = Gson()
    private val gson = GsonBuilder().registerTypeAdapter(Data::class.java, object : TypeAdapter<Data>() {
        override fun read(reader: JsonReader): Data {
            reader.isLenient = false
            return parse(readStrictValue(reader))
        }
        override fun write(writer: JsonWriter, value: Data?) = writerGson.toJson(value, Data::class.java, writer)
    }).create()

    private data class Row(
        val worldId: String,
        val chunkX: Int,
        val chunkZ: Int,
        val hostId: String,
        val guestId: String,
        val status: String,
        val createdAt: Long,
        val changedAt: Long,
        val deadlineAt: Long?,
        val conquerorId: String?,
        val pausedAt: Long?,
        val offerSize: Int
    ) {
        fun toDomain() = MfEmbassy(
            UUID.fromString(worldId), chunkX, chunkZ, MfFactionId(hostId), MfFactionId(guestId),
            MfEmbassyStatus.valueOf(status), createdAt, changedAt, deadlineAt,
            conquerorId?.let(::MfFactionId), pausedAt, offerSize
        ).also { it.validateStoredEmbassy() }
    }

    private data class Data(val embassies: MutableList<Row> = mutableListOf())

    /** Tree parsers collapse duplicate fields before validation, so detect them in the stream. */
    private fun readStrictValue(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val value = JsonObject()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(!value.has(name)) { "Duplicate embassy JSON field $name" }
                value.add(name, readStrictValue(reader))
            }
            reader.endObject()
            value
        }
        JsonToken.BEGIN_ARRAY -> {
            val value = JsonArray()
            reader.beginArray()
            while (reader.hasNext()) value.add(readStrictValue(reader))
            reader.endArray()
            value
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw IllegalArgumentException("Malformed embassy JSON value")
    }

    private fun parse(json: JsonElement): Data {
        require(json.isJsonObject) { "Embassy JSON root must be an object" }
        val root = json.asJsonObject
        require(root.keySet() == setOf("embassies")) { "Embassy JSON must contain only an embassies array" }
        val entries = root.get("embassies")
        require(entries.isJsonArray) { "Embassy JSON embassies must be an array" }
        val rows = entries.asJsonArray.map { entry ->
            require(entry.isJsonObject) { "Embassy JSON row must be an object" }
            val row = entry.asJsonObject
            require(row.keySet().all { it in ROW_FIELDS }) { "Unknown embassy JSON row field" }
            Row(
                row.string("worldId"), row.integer("chunkX"), row.integer("chunkZ"),
                row.string("hostId"), row.string("guestId"), row.string("status"),
                row.long("createdAt"), row.long("changedAt"), row.optionalLong("deadlineAt"),
                row.optionalString("conquerorId"), row.optionalLong("pausedAt"),
                if (row.has("offerSize")) row.integer("offerSize") else 1
            )
        }.toMutableList()
        val domains = rows.map(Row::toDomain)
        require(domains.size == domains.distinctBy { Triple(it.worldId, it.chunkX, it.chunkZ) }.size) {
            "Duplicate embassy chunk in JSON storage"
        }
        return Data(rows)
    }

    private fun JsonObject.required(name: String): JsonElement = requireNotNull(get(name)) {
        "Missing embassy JSON field $name"
    }

    private fun JsonObject.string(name: String): String {
        val value = required(name)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid embassy JSON string $name" }
        return value.asString
    }

    private fun JsonObject.integer(name: String): Int {
        val value = required(name)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid embassy JSON integer $name" }
        return value.asBigDecimal.intValueExact()
    }

    private fun JsonObject.long(name: String): Long {
        val value = required(name)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid embassy JSON long $name" }
        return value.asBigDecimal.longValueExact()
    }

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.let { string(name) }

    private fun JsonObject.optionalLong(name: String): Long? =
        get(name)?.takeUnless { it.isJsonNull }?.let { long(name) }

    private fun load(): Data = storageManager.loadJsonData(fileName, "embassies", gson, Data::class.java) { Data() }

    override fun getAll(): List<MfEmbassy> = load().embassies.map(Row::toDomain)

    override fun upsert(embassy: MfEmbassy) = applyChanges(listOf(embassy), emptyList())

    override fun delete(worldId: UUID, chunkX: Int, chunkZ: Int) = storageManager.withFileLock(fileName) {
        val data = load()
        data.embassies.removeIf { it.worldId == worldId.toString() && it.chunkX == chunkX && it.chunkZ == chunkZ }
        storageManager.writeJsonFile(fileName, data, null)
    }

    override fun applyChanges(upserts: List<MfEmbassy>, deletes: List<MfEmbassy>) = storageManager.withFileLock(fileName) {
        upserts.forEach { it.validateStoredEmbassy() }
        val data = load()
        val replacedKeys = (upserts + deletes).mapTo(HashSet()) { Triple(it.worldId.toString(), it.chunkX, it.chunkZ) }
        data.embassies.removeIf { Triple(it.worldId, it.chunkX, it.chunkZ) in replacedKeys }
        data.embassies.addAll(upserts.map { it.toRow() })
        storageManager.writeJsonFile(fileName, data, null)
    }

    private fun MfEmbassy.toRow() = Row(
        worldId.toString(), chunkX, chunkZ, hostId.value, guestId.value, status.name,
        createdAt, changedAt, deadlineAt, conquerorId?.value, pausedAt, offerSize
    )

    companion object {
        private val ROW_FIELDS = setOf("worldId", "chunkX", "chunkZ", "hostId", "guestId", "status",
            "createdAt", "changedAt", "deadlineAt", "conquerorId", "pausedAt", "offerSize")
    }
}
