package fraudguard.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Settings live in the config directory as JSON. Read from the working directory, or from the classpath when packaged. */
object Settings {
    val JSON = Json { prettyPrint = true }

    fun load(name: String): JsonObject {
        val f = File("config/$name.json")
        val text = if (f.exists()) f.readText() else Settings::class.java.getResource("/config/$name.json")?.readText()
            ?: error("config/$name.json not found")
        return Json.parseToJsonElement(text).jsonObject
    }

    /** A short hash of the given settings files, written into every report. */
    fun sha(vararg names: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (n in names) md.update(File("config/$n.json").readBytes())
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}

fun JsonObject.obj(k: String): JsonObject = this[k]?.jsonObject ?: error("missing $k")
fun JsonObject.d(k: String): Double = this[k]?.jsonPrimitive?.double ?: error("missing $k")
fun JsonObject.i(k: String): Int = this[k]?.jsonPrimitive?.int ?: error("missing $k")
fun JsonObject.b(k: String): Boolean = this[k]?.jsonPrimitive?.boolean ?: error("missing $k")
fun JsonObject.s(k: String): String = this[k]?.jsonPrimitive?.content ?: error("missing $k")
fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: error("missing $k")
fun JsonObject.has(k: String): Boolean = k in this
fun num(x: Number): JsonElement = JsonPrimitive(x)
