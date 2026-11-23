#!/bin/bash
# Старт контейнера: поднимаем Jupyter, создаём ядра для скриптов и прогреваем их —
# исполняем прелюдию языка (Report + %use klaxon для kotlin) дважды, чтобы компилятор
# и зависимости были готовы к первому запросу приложения.
# Пул сервиса усыновляет эти ядра (GET /api/kernels по имени kernelspec).

MCP_URL="http://127.0.0.1:4040/mcp"
API="http://127.0.0.1:4040/api/kernels"
AUTH="Authorization: Bearer MY_TOKEN"

jupyter lab --port=4040 --ip=0.0.0.0 --IdentityProvider.token=MY_TOKEN --no-browser &
SERVER_PID=$!
trap 'kill $SERVER_PID' TERM INT

until curl -sf http://127.0.0.1:4040/api/kernelspecs -H "$AUTH" > /dev/null 2>&1; do
  sleep 0.5
done

# $1=имя ядра $2=файл прелюдии $3=строка привязки payload
warm() {
  KID=$(curl -s -X POST "$API" -H "$AUTH" -H "Content-Type: application/json" \
        -d "{\"name\": \"$1\"}" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id", ""))')
  if [ -z "$KID" ]; then
    echo "не удалось создать ядро $1"
    return
  fi

  WARM_BODY=$(python3 - "$2" "$3" "$KID" <<'PY'
import json, sys
prelude_path, binding, kernel_id = sys.argv[1:4]
code = open(prelude_path, encoding="utf-8").read() + "\n\n" + binding + "\n\n1\n2\n"
print(json.dumps({
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": {"name": "execute_code", "arguments": {
        "code": code, "kernel_id": kernel_id, "timeout": 300,
    }},
}))
PY
)
  curl -s --max-time 300 -X POST "$MCP_URL" -H "$AUTH" -H "Content-Type: application/json" \
       -H "Accept: application/json, text/event-stream" -d "$WARM_BODY" > /dev/null \
    && echo "ядро $1 прогрето ($KID)"
}

warm "python3" "/opt/preludes/python-prelude.py" 'payload = load_payload(None)'
warm "kotlin"  "/opt/preludes/kotlin-prelude.kts" 'payload = decode_payload(null)'

wait $SERVER_PID
