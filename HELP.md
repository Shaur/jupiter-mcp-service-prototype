# RemoteCodeExecutionServer

Spring Boot сервис — REST-обёртка над [Jupyter MCP Server](https://github.com/jupyter-server/jupyter-mcp-server).
Принимает код по HTTP, исполняет его в ядрах Jupyter (python, kotlin) через MCP-протокол и возвращает текстовый вывод.

## Структура

```
com.example.rpcs
├── web      — REST-контроллеры
├── mcp      — McpService: вызовы tools/call к Jupyter MCP,
│              создание и прогрев ядер через Jupyter REST API
├── scripts  — markdown-скрипты: YAML front matter (id, language) + блок кода
└── config   — McpProperties, ScriptProperties (application.properties)
```

## API

| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/execute` | Тело `text/plain` — код. Опциональный заголовок `X-Kernel-Id` — исполнить в конкретном ядре. Ответ: вывод. |
| POST | `/api/scripts/{id}/run` | Запускает `scripts/<id>.md` (язык берётся из front matter). Опциональное JSON-тело — payload для скриптов-шаблонов (плейсхолдер `__PAYLOAD_B64__`); если скрипт отвечает JSON-строкой, ответ отдаётся как `application/json`. Исполнение — в свободном ядре пула. |

Ошибки: 404 — скрипт не найден, 400 — неизвестный язык, битый markdown (в т.ч. несколько блоков кода) или скрипт-шаблон без плейсхолдера payload, 502 — ошибка на стороне Jupyter.

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

Токен в `Dockerfile` и в `application.properties` должны совпадать.

## Запуск Jupyter MCP (Docker)

Образ собирается из `Dockerfile`: `jupyter/minimal-notebook` + `jupyter-mcp-server` + ядро Kotlin (`kotlin-jupyter-kernel`).
Сервис держит **пул ядер** по каждому языку: при первом обращении к языку ядро создаётся, прогревается и
возвращается в пул, последующие запуски переиспользуют его. Под нагрузкой пул растёт до `mcp.kernel-pool-max-size`;
когда все ядра заняты, запуск ждёт освобождения (`mcp.kernel-acquire-timeout-seconds`). Состояние между запусками
не сохраняется (следующий запуск может попасть в другое ядро пула).

### Замеры: пул против ядра-на-каждый-запуск

Альтернативой пулу было ephemeral-ядро (создать → исполнить → удалить на каждый запуск).
Замеры ниже — обоснование выбора пула: curl `time_total` на локальной машине, числа ориентировочные.

| Язык | Сценарий | Ядро на запуск, с | Пул, с | Ускорение |
|-------|----------|------------------:|-------:|-----------|
| Python | hello-python, одиночный запуск (холодный пул) | 0.96 | 0.75 | ≈ |
| Python | lines-identification (payload), повторные | 0.89 | 0.23 | ~3.9× |
| Python | hello-python, повторные | 0.91 | 0.23 | ~4× |
| Python | hello-python ×3 параллельно (wall) | 0.97 | 0.70 | 1.4× |
| Kotlin | lines-identification, первый запуск (создание JVM-ядра) | 4.88 | 4.18 | ≈ |
| Kotlin | lines-identification, повторные | 4.78 | 0.37 | ~13× |

«Первый запуск» — единственный платит создание ядра (для Kotlin — старт JVM): эта цена платится
один раз за жизнь ядра в пуле, а не на каждый запуск. Повторные значения — среднее по 2–3 прогонам.
Параллельная строка — wall time трёх одновременных запросов.

> **Важно при деплое в k8s**: Jupyter MCP должен работать в **одной реплике** (StatefulSet `replicas: 1`, без HPA).
> Ядра — pod-local состояние: идентификаторы ядер существуют только внутри своего пода, и при масштабировании
> `kernel_id`-исполнение ломается независимо от кэша на нашем сервисе. Если потребуется горизонтальное масштабирование
> execution — это отдельная архитектурная задача (sessionAffinity, headless Service + pin на под, либо sandbox-варианты сервера).

```bash
docker compose up -d --build
```

или вручную:

```bash
docker build -t jupyter-mcp .
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
