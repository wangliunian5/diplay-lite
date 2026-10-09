package com.shilapi.diplay.lite

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 方控按键配置（M6k22，移植自上游 fork SteeringProfiles，JSON 与其完全同构）。
 *
 * Lite 裁剪：无上传 outbox / JobScheduler（不外发配置），无 AtomicFile（API17+，
 * 改用 临时文件+rename 原子写）。schema/backend 字段保留同构值，便于将来与上游互换。
 */
internal data class SteeringBinding(
    val operation: String,
    val keyCode: Int,
    val event: Int,
    val source: String = "logcat",
    val logTag: String = "",
    val broadcastAction: String = "",
    val keyExtra: String = "",
    val eventExtra: String = "",
    val logContains: List<String> = emptyList(),
) {
    fun json(): JSONObject = JSONObject()
        .put("operation", operation).put("keyCode", keyCode).put("event", event)
        .put("source", source).put("logTag", logTag)
        .put("broadcastAction", broadcastAction).put("keyExtra", keyExtra).put("eventExtra", eventExtra)
        .put("logContains", JSONArray(logContains))

    val inputId: String get() = listOf(source, keyCode.toString(), logTag, broadcastAction, keyExtra, eventExtra)
        .joinToString("\u0000") + "\u0000" + logContains.sorted().joinToString("\u0000")

    companion object {
        // 1.0.29：音量+/- 走本地 AudioManager（车机媒体音量流），不经 CarPlay 协议。
        val operations = listOf("volume_up", "volume_down", "play_pause", "previous", "next", "siri")

        fun fromJson(json: JSONObject) = SteeringBinding(
            json.getString("operation"), json.getInt("keyCode"), json.getInt("event"),
            json.getString("source"), json.optString("logTag"), json.optString("broadcastAction"),
            json.optString("keyExtra"), json.optString("eventExtra"),
            json.optJSONArray("logContains")?.let { array ->
                require(array.length() <= 4)
                (0 until array.length()).map(array::getString)
            }.orEmpty(),
        ).also {
            require(it.operation in operations && it.event in 0..4)
            // key_event：设置页学习向导经本地 KeyEvent 学到的键（M6k25）
            require(it.source in listOf("broadcast", "logcat", "session", "key_event"))
            require(it.keyCode in 1..1_000_000 || (it.keyCode == 0 && it.source == "logcat" && it.logContains.isNotEmpty()))
            require(it.source != "logcat" || it.logTag.isNotBlank())
            require(it.logTag.length <= 100 && it.logTag.none(Char::isISOControl))
            require(it.broadcastAction.length <= 160 && it.broadcastAction.none(Char::isISOControl))
            require(it.keyExtra.length <= 120 && it.eventExtra.length <= 120)
            require(it.keyExtra.none(Char::isISOControl) && it.eventExtra.none(Char::isISOControl))
            require(it.source != "broadcast" || (it.broadcastAction.matches(Regex("[A-Za-z0-9_.]+")) && it.keyExtra.isNotBlank()))
            require(it.logContains.size <= 4 && (it.source == "logcat" || it.logContains.isEmpty()))
            it.logContains.forEach { text -> require(text.isNotBlank() && text.length <= 160 && text.none(Char::isISOControl)) }
        }
    }
}

internal data class SteeringProfile(
    val carModel: String,
    val headUnitModel: String,
    val manufacturer: String = Build.MANUFACTURER.orEmpty(),
    val firmware: String = Build.DISPLAY.orEmpty(),
    val androidVersion: String = Build.VERSION.RELEASE.orEmpty(),
    val savedAt: Long = System.currentTimeMillis(),
    val bindings: List<SteeringBinding> = emptyList(),
) {
    fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("backend", "system_key_events")
        .put("carModel", carModel).put("headUnitModel", headUnitModel)
        .put("manufacturer", manufacturer).put("firmware", firmware)
        .put("androidVersion", androidVersion).put("savedAt", savedAt)
        .put("bindings", JSONArray().also { array -> bindings.forEach { array.put(it.json()) } })

    fun validated(): SteeringProfile = also {
        require(carModel.isNotBlank() && headUnitModel.isNotBlank())
        listOf(carModel, headUnitModel, manufacturer, firmware, androidVersion).forEach { value ->
            require(value.length <= 120 && value.none(Char::isISOControl))
        }
        require(bindings.size in 1..4 && bindings.map { it.operation }.distinct().size == bindings.size)
        require(bindings.map { it.inputId }.distinct().size == bindings.size)
        require(savedAt in 1..999_999_999_999_999L)
        bindings.forEach { SteeringBinding.fromJson(it.json()) }
    }

    companion object {
        fun fromJson(json: JSONObject): SteeringProfile {
            require(json.getInt("schemaVersion") == 1 && json.getString("backend") == "system_key_events")
            val array = json.getJSONArray("bindings")
            require(array.length() <= 4)
            return SteeringProfile(
                json.getString("carModel"), json.getString("headUnitModel"),
                json.optString("manufacturer"), json.optString("firmware"),
                json.optString("androidVersion"), json.getLong("savedAt"),
                (0 until array.length()).map { SteeringBinding.fromJson(array.getJSONObject(it)) },
            ).validated()
        }
    }
}

/** 方控配置持久化：filesDir/steering-profiles/ + SharedPreferences 记录当前 profile。 */
internal object SteeringStore {

    private const val PREFS = "steering_profiles"
    private const val MAX_BYTES = 32 * 1024

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun directory(context: Context) = File(context.filesDir, "steering-profiles").apply { mkdirs() }

    @Synchronized
    fun loadEnabled(context: Context): SteeringProfile? = runCatching {
        if (!enabled(context)) return@runCatching null
        val name = prefs(context).getString("active", null) ?: return@runCatching null
        require(File(name).name == name && name.endsWith(".json"))
        val file = File(directory(context), name)
        if (!file.isFile) return@runCatching null
        SteeringProfile.fromJson(JSONObject(file.readBytes().toString(Charsets.UTF_8)))
    }.onFailure {
        LiteLog.log("steering", "方控配置加载失败：${it.message}")
    }.getOrNull()

    @Synchronized
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    @Synchronized
    fun save(context: Context, profile: SteeringProfile) {
        profile.validated()
        val bytes = profile.json().toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val name = "active.json"
        val tmp = File(directory(context), "$name.tmp")
        val dst = File(directory(context), name)
        tmp.writeBytes(bytes)
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("方控配置写入失败")
        prefs(context).edit().putString("active", name).putBoolean("enabled", true).commit()
    }

    @Synchronized
    fun clear(context: Context) {
        File(directory(context), "active.json").delete()
        prefs(context).edit().putBoolean("enabled", false).commit()
    }
}
