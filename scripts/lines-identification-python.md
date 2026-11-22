---
id: lines-identification-python
language: python
---

```python
import base64
import json

data = json.loads(base64.b64decode("__PAYLOAD_B64__").decode("utf-8"))
lines = data["lines"]

first_seen = {}
for number, line in enumerate(lines, start=1):
    if line in first_seen:
        report = {"line": first_seen[line],
                  "message": f"Строка {first_seen[line]} дублирует строку {number}"}
        break
    first_seen[line] = number
else:
    report = {"line": -1, "message": "Дубликаты не найдены"}

print(json.dumps(report, ensure_ascii=False))
```
