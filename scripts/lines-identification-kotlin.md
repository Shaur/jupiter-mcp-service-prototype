---
id: lines-identification-kotlin
language: kotlin
---

```kotlin
val lines = payload["lines"] as List<String>

val seen = mutableMapOf<String, Int>()
for ((index, line) in lines.withIndex()) {
    val number = index + 1
    if (seen.containsKey(line)) {
        report.invalid("Строка ${seen[line]} дублирует строку $number")
        break
    }
    seen[line] = number
}

report
```
