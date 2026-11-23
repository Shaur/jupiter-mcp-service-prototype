# RemoteCodeExecutionServer

Spring Boot сервис — REST-обёртка над [Jupyter MCP Server](https://github.com/jupyter-server/jupyter-mcp-server).
Принимает код по HTTP, исполняет его в ядрах Jupyter (python, kotlin) через MCP-протокол и возвращает текстовый вывод.

## Структура

```
com.example.rpcs
├── web      — REST-контроллеры
├── mcp      — McpClient: асинхронный клиент протокола и REST ядер (CompletableFuture, OkHttp);
│              McpService: пул ядер и синхронная обёртка для контроллеров
├── scripts  — markdown-скрипты: YAML front matter (id, language) + блок кода
└── config   — McpProperties, ScriptProperties (application.properties)
```

## API

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/execute` | Тело `text/plain` — код. Опциональный заголовок `X-Kernel-Id` — исполнить в конкретном ядре. Ответ: вывод. |
| POST | `/api/scripts/{id}/run` | Запускает `scripts/<id>.md` (язык берётся из front matter). Опциональное JSON-тело — payload, в скрипте доступен как переменная `payload`. Скрипты-правила отвечают JSON `{"valid", "message"}` — ответ отдаётся как `application/json`. Исполнение — в свободном ядре пула. |

Ошибки: 404 — скрипт не найден, 400 — неизвестный язык или битый markdown (в т.ч. несколько блоков кода), 502 — ошибка на стороне Jupyter.

Markdown-скрипт выглядит так:

````markdown
---
id: hello-kotlin
language: kotlin
---

```kotlin
val x = 6 * 7
println("hello from kotlin: $x")
```
````

Примеры запросов — `requests.http`.

### Флюент-API для скриптов-правил

Сервис инжектит в каждый скрипт прелюдию языка (`src/main/resources/runtime/`), которая даёт:

- **`payload`** — тело запроса, десериализованное прелюдией (python — stdlib `json`, kotlin — `%use klaxon`);
  от сервиса payload приходит base64-строкой — алфавит base64 не содержит кавычек, поэтому встраивается
  в код без экранирования и не может сломать скрипт;
- **`report`** — аккумулятор результата: `report.invalid("причина")` / `report.valid()`.

Контракт: последняя строка скрипта — `report`; сервис возвращает `{"valid": ..., "message": "..."}`.

````markdown
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
````

## Настройка

`src/main/resources/application.properties`:

| Свойство | Значение по умолчанию | Описание |
|----------|----------------------|----------|
| `mcp.url` | `http://127.0.0.1:4040/mcp` | MCP-эндпоинт Jupyter |
| `mcp.api-url` | `http://127.0.0.1:4040` | Базовый адрес Jupyter REST API (создание ядер) |
| `mcp.token` | `MY_TOKEN` | Bearer-токен Jupyter |
| `mcp.connect-timeout-seconds` | 10 | Таймаут подключения |
| `mcp.read-timeout-seconds` | 300 | Таймаут чтения (запас на долгие вычисления) |
| `mcp.execution-timeout-seconds` | 120 | Таймаут исполнения, передаётся в `execute_code` (сек) |
| `mcp.kernel-pool-min-size` | 1 | Сколько ядер каждого языка прогревается заранее |
| `mcp.kernel-pool-max-size` | 4 | Максимум ядер на язык; далее запуск ждёт освобождения |
| `mcp.kernel-acquire-timeout-seconds` | 60 | Сколько ждать свободное ядро, если пул исчерпан |
| `scripts.dir` | `scripts` | Каталог markdown-скриптов |

Токен в `mcp-local/Dockerfile` и в `application.properties` должны совпадать.

## Запуск Jupyter MCP (Docker)

Образ собирается из `mcp-local/Dockerfile`: `jupyter/minimal-notebook` + `jupyter-mcp-server` + ядро Kotlin (`kotlin-jupyter-kernel`).
На этапе сборки образ предзагружает зависимости Kotlin-прелюдии — klaxon (`mcp-local/kotlin-warmup.ipynb` + `jupyter nbconvert --execute`),
поэтому первый запуск Kotlin-скрипта с payload работает без доступа к Maven Central.
Сервис держит **пул ядер** по каждому языку: при первом обращении к языку ядро создаётся, прогревается и
возвращается в пул, последующие запуски переиспользуют его. Под нагрузкой пул растёт до `mcp.kernel-pool-max-size`;
когда все ядра заняты, запуск ждёт освобождения (`mcp.kernel-acquire-timeout-seconds`). Состояние между запусками
не сохраняется (следующий запуск может попасть в другое ядро пула).

При старте контейнера `docker-entrypoint.sh` создаёт ядра `python3`/`kotlin` и **дважды исполняет прелюдию
языка** (компилятор + klaxon). Сервис при первом обращении **усыновляет** эти ядра (`GET /api/kernels` по имени
kernelspec) — тёплое состояние переживает рестарт приложения; если прогретых ядер меньше `mcp.kernel-pool-min-size`,
сервис добирает пул сам.

### Замеры: пул против ядра-на-каждый-запуск

Альтернативой пулу было ephemeral-ядро (создать → исполнить → удалить на каждый запуск).
Замеры ниже — обоснование выбора пула: curl `time_total` на локальной машине, числа ориентировочные.

| Язык | Сценарий | Ядро на запуск, с | Пул, с | Ускорение |
|-------|----------|------------------:|-------:|-----------|
| Python | hello-python, одиночный запуск (тёплый пул после старта контейнера) | 0.96 | 0.23 | ~4.2× |
| Python | lines-identification (payload), повторные | 0.89 | 0.23 | ~3.9× |
| Python | hello-python, повторные | 0.91 | 0.23 | ~4× |
| Python | hello-python ×3 параллельно (wall) | 0.97 | 0.75 | 1.3× |
| Kotlin | lines-identification, первый запуск (JVM + klaxon уже прогреты контейнером) | 4.88 | 0.86 | ~5.7× |
| Kotlin | lines-identification, повторные | 4.78 | 0.44 | ~11× |

Колонка «ядро на запуск» — исторический базлайн ephemeral-реализации (до флюент-API); вывод не меняется:
разница — только в цене создания ядра, контракт на неё не влияет.
«Первый запуск» Kotlin — это старт JVM-ядра и загрузка klaxon; благодаря прогреву на старте контейнера
и усыновлению (`Усыновлено прогретое ядро …` в логе) пользовательский запрос получает готовое ядро.
Повторные значения — среднее по 2–3 прогонам. Параллельная строка — wall time трёх одновременных запросов.

> **Важно при деплое в k8s**: Jupyter MCP должен работать в **одной реплике** (StatefulSet `replicas: 1`, без HPA).
> Ядра — pod-local состояние: идентификаторы ядер существуют только внутри своего пода, и при масштабировании
> `kernel_id`-исполнение ломается независимо от кэша на нашем сервисе. Если потребуется горизонтальное масштабирование
> execution — это отдельная архитектурная задача (sessionAffinity, headless Service + pin на под, либо sandbox-варианты сервера).

```bash
docker compose -f mcp-local/docker-compose.yml up -d --build
```

или вручную (контекст сборки — корень проекта, Dockerfile берётся из `mcp-local`):

```bash
docker build -f mcp-local/Dockerfile -t jupyter-mcp .
docker run -d --name jupyter-mcp -p 4040:4040 jupyter-mcp
```

Проверка:

```bash
curl -s http://127.0.0.1:4040/api/kernelspecs -H "Authorization: Bearer MY_TOKEN"
# ожидаются kernelspecs: python3, kotlin
```

## Запуск приложения

```bash
./gradlew bootRun
```

или jar:

```bash
./gradlew bootJar
java -jar build/libs/RemoteCodeExecutionServer-0.0.1-SNAPSHOT.jar
```

Проверка:

```bash
curl -s -X POST localhost:8080/api/execute -H 'Content-Type: text/plain' --data-binary 'print(2 + 2)'
```
