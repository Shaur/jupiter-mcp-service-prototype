---
id: lines-identification-python
language: python
---

```python
lines = payload["lines"]

seen = {}
for number, line in enumerate(lines, start=1):
    if line in seen:
        report.invalid(f"Строка {seen[line]} дублирует строку {number}")
        break
    seen[line] = number

report
```
