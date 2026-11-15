package com.example.network

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

class HistoryRecordDtoAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): HistoryRecordDto? {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<Unit>()
            return null
        }
        var issueNumber: String? = null
        var number: Int? = null
        var color: String? = null
        var premium: String? = null
        var sum: Int? = null
        var blockId: String? = null
        var blockNumber: String? = null
        var blockTimestamp: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "issueNumber" -> issueNumber = readFlexibleString(reader)
                "number" -> number = readFlexibleInt(reader)
                "color" -> color = readFlexibleString(reader)
                "premium" -> premium = readFlexibleString(reader)
                "sum" -> sum = readFlexibleInt(reader)
                "blockId" -> blockId = readFlexibleString(reader)
                "blockNumber" -> blockNumber = readFlexibleString(reader)
                "blockTimestamp" -> blockTimestamp = readFlexibleString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return HistoryRecordDto(
            issueNumber = issueNumber,
            number = number,
            color = color,
            premium = premium,
            sum = sum,
            blockId = blockId,
            blockNumber = blockNumber,
            blockTimestamp = blockTimestamp
        )
    }

    @ToJson
    fun toJson(writer: JsonWriter, value: HistoryRecordDto?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("issueNumber").value(value.issueNumber)
        writer.name("number").value(value.number)
        writer.name("color").value(value.color)
        writer.name("premium").value(value.premium)
        writer.name("sum").value(value.sum)
        writer.name("blockId").value(value.blockId)
        writer.name("blockNumber").value(value.blockNumber)
        writer.name("blockTimestamp").value(value.blockTimestamp)
        writer.endObject()
    }

    private fun readFlexibleInt(reader: JsonReader): Int? = when (reader.peek()) {
        JsonReader.Token.NULL -> {
            reader.nextNull<Unit>()
            null
        }
        JsonReader.Token.NUMBER -> runCatching { reader.nextInt() }.getOrElse {
            runCatching { reader.nextDouble().toInt() }.getOrNull()
        }
        JsonReader.Token.STRING -> reader.nextString().trim().toIntOrNull()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readFlexibleString(reader: JsonReader): String? = when (reader.peek()) {
        JsonReader.Token.NULL -> {
            reader.nextNull<Unit>()
            null
        }
        JsonReader.Token.STRING -> reader.nextString()
        JsonReader.Token.NUMBER -> reader.nextString()
        JsonReader.Token.BOOLEAN -> reader.nextBoolean().toString()
        else -> {
            reader.skipValue()
            null
        }
    }
}

/**
 * Type-safe representation for optional dynamic JSON fields (such as bonusRates or todayJackpots)
 * so unexpected array/object/scalar shapes never cause Moshi deserialization crashes.
 */
sealed interface FlexibleJsonValue {
    data object NullValue : FlexibleJsonValue
    data class BoolValue(val value: Boolean) : FlexibleJsonValue
    data class NumberValue(val value: Double) : FlexibleJsonValue
    data class StringValue(val value: String) : FlexibleJsonValue
    data class ArrayValue(val items: List<FlexibleJsonValue>) : FlexibleJsonValue
    data class ObjectValue(val fields: Map<String, FlexibleJsonValue>) : FlexibleJsonValue
}

class FlexibleJsonValueAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): FlexibleJsonValue {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                FlexibleJsonValue.NullValue
            }
            JsonReader.Token.BOOLEAN -> FlexibleJsonValue.BoolValue(reader.nextBoolean())
            JsonReader.Token.NUMBER -> FlexibleJsonValue.NumberValue(reader.nextDouble())
            JsonReader.Token.STRING -> FlexibleJsonValue.StringValue(reader.nextString())
            JsonReader.Token.BEGIN_ARRAY -> {
                val items = mutableListOf<FlexibleJsonValue>()
                reader.beginArray()
                while (reader.hasNext()) {
                    items.add(fromJson(reader))
                }
                reader.endArray()
                FlexibleJsonValue.ArrayValue(items)
            }
            JsonReader.Token.BEGIN_OBJECT -> {
                val fields = linkedMapOf<String, FlexibleJsonValue>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    fields[key] = fromJson(reader)
                }
                reader.endObject()
                FlexibleJsonValue.ObjectValue(fields)
            }
            else -> {
                reader.skipValue()
                FlexibleJsonValue.NullValue
            }
        }
    }

    @ToJson
    fun toJson(writer: JsonWriter, value: FlexibleJsonValue?) {
        when (value) {
            null, FlexibleJsonValue.NullValue -> writer.nullValue()
            is FlexibleJsonValue.BoolValue -> writer.value(value.value)
            is FlexibleJsonValue.NumberValue -> writer.value(value.value)
            is FlexibleJsonValue.StringValue -> writer.value(value.value)
            is FlexibleJsonValue.ArrayValue -> {
                writer.beginArray()
                value.items.forEach { toJson(writer, it) }
                writer.endArray()
            }
            is FlexibleJsonValue.ObjectValue -> {
                writer.beginObject()
                value.fields.forEach { (k, v) ->
                    writer.name(k)
                    toJson(writer, v)
                }
                writer.endObject()
            }
        }
    }
}

@JsonClass(generateAdapter = true)
data class HistoryPageResponse(
    @param:Json(name = "code") val code: Int,
    @param:Json(name = "msg") val msg: String? = null,
    @param:Json(name = "msgCode") val msgCode: Int? = null,
    @param:Json(name = "serviceTime") val serviceTime: Long? = null,
    @param:Json(name = "data") val data: HistoryPageData? = null
)

@JsonClass(generateAdapter = true)
data class HistoryPageData(
    @param:Json(name = "pageNo") val pageNo: Int = 1,
    @param:Json(name = "totalPage") val totalPage: Int = 0,
    @param:Json(name = "totalCount") val totalCount: Int = 0,
    @param:Json(name = "list") val list: List<HistoryRecordDto> = emptyList()
)

data class HistoryRecordDto(
    val issueNumber: String? = null,
    val number: Int? = null,
    val color: String? = null,
    val premium: String? = null,
    val sum: Int? = null,
    val blockId: String? = null,
    val blockNumber: String? = null,
    val blockTimestamp: String? = null
)

@JsonClass(generateAdapter = true)
data class GetPanelResponse(
    @param:Json(name = "data") val data: PanelData? = null,
    @param:Json(name = "code") val code: Int? = null,
    @param:Json(name = "msg") val msg: String? = null,
    @param:Json(name = "msgCode") val msgCode: Int? = null,
    @param:Json(name = "serviceTime") val serviceTime: Long? = null
)

@JsonClass(generateAdapter = true)
data class PanelData(
    @param:Json(name = "phase") val phase: String? = null,
    @param:Json(name = "serverTime") val serverTime: Long? = null,
    @param:Json(name = "startTime") val startTime: Long? = null,
    @param:Json(name = "endTime") val endTime: Long? = null,
    @param:Json(name = "issueNumber") val issueNumber: String? = null,
    @param:Json(name = "currentIssueNumber") val currentIssueNumber: String? = null,
    @param:Json(name = "snapshot") val snapshot: PanelSnapshot? = null,
    @param:Json(name = "account") val account: PanelAccount? = null,
    @param:Json(name = "unlockedNumbers") val unlockedNumbers: List<Int?>? = null,
    @param:Json(name = "repeatNumbers") val repeatNumbers: List<Int?>? = null,
    @param:Json(name = "bonusRates") val bonusRates: FlexibleJsonValue? = null,
    @param:Json(name = "firstBetEnergy") val firstBetEnergy: Double? = null,
    @param:Json(name = "earnCap") val earnCap: Double? = null,
    @param:Json(name = "cumulativeBetAmount") val cumulativeBetAmount: Double? = null,
    @param:Json(name = "cumulativeBetEnergy") val cumulativeBetEnergy: Double? = null,
    @param:Json(name = "todayJackpots") val todayJackpots: FlexibleJsonValue? = null
)

@JsonClass(generateAdapter = true)
data class PanelSnapshot(
    @param:Json(name = "snapNo") val snapNo: Int? = null,
    @param:Json(name = "list") val list: List<SnapshotItem?>? = null
)

@JsonClass(generateAdapter = true)
data class SnapshotItem(
    @param:Json(name = "number") val number: Int? = null,
    @param:Json(name = "percent") val percent: Double? = null,
    @param:Json(name = "rank") val rank: Int? = null,
    @param:Json(name = "medal") val medal: Int? = null,
    @param:Json(name = "prevRank") val prevRank: Int? = null
)

@JsonClass(generateAdapter = true)
data class PanelAccount(
    @param:Json(name = "remaining") val remaining: Double? = null,
    @param:Json(name = "earned") val earned: Double? = null,
    @param:Json(name = "used") val used: Double? = null,
    @param:Json(name = "participated") val participated: Boolean? = null
)

data class ValidatedSnapshotItem(
    val number: Int,
    val percent: Double,
    val rank: Int,
    val medal: Int,
    val prevRank: Int?
)
