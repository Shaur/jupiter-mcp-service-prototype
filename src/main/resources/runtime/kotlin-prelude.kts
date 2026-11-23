%use klaxon(5.5)

import java.util.Base64
import com.beust.klaxon.Klaxon

class Report {
    /** Аккумулятор результата правила. Последняя строка скрипта — `report`. */
    var valid = true
    var message: String? = null

    fun valid() {
        valid = true
        message = null
    }

    fun invalid(message: String) {
        valid = false
        this.message = message
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    override fun toString(): String {
        val sb = StringBuilder("{\"valid\": ").append(valid)
        if (message != null) sb.append(", \"message\": \"").append(esc(message!!)).append('"')
        return sb.append('}').toString()
    }
}

fun decode_payload(b64: String?): Map<String, Any?> =
    if (b64 == null) emptyMap()
    else Klaxon().parse<Map<String, Any?>>(String(Base64.getDecoder().decode(b64))) ?: emptyMap()

val report = Report()
var payload: Map<String, Any?> = emptyMap()
