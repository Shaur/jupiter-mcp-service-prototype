---
id: lines-identification-kotlin
language: kotlin
---

```kotlin
%use klaxon(5.5)

import com.beust.klaxon.Klaxon
import java.util.Base64

data class Payload(val lines: List<String>)
data class Report(val line: Int, val message: String)

val payload = String(Base64.getDecoder().decode("__PAYLOAD_B64__"))
val lines = Klaxon().parse<Payload>(payload)!!.lines

val firstSeen = mutableMapOf<String, Int>()
var report = Report(-1, "Дубликаты не найдены")
for ((index, line) in lines.withIndex()) {
    val number = index + 1
    val original = firstSeen[line]
    if (original != null) {
        report = Report(original, "Строка $original дублирует строку $number")
        break
    }
    firstSeen[line] = number
}

println(Klaxon().toJsonString(report))
```
