#!/bin/sh
set -e

ENV_FILE="${ENV_FILE:-$HOME/relay-bridge/.env}"
if [ ! -f "$ENV_FILE" ]; then
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    if [ -f "$SCRIPT_DIR/../.env" ]; then
        ENV_FILE="$SCRIPT_DIR/../.env"
    fi
fi

DEVICE_TOKEN=""
if [ -f "$ENV_FILE" ]; then
    DEVICE_TOKEN="$(grep -E '^DEVICE_TOKEN=' "$ENV_FILE" | head -n 1 | cut -d= -f2- | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
fi

if [ -z "$DEVICE_TOKEN" ]; then
    echo "Warning: DEVICE_TOKEN is empty or not found in $ENV_FILE" >&2
fi

BRIDGE_URL="${BRIDGE_URL:-http://localhost:8093}"
INTERVAL="${POLL_INTERVAL:-12}"

PYTHON_BIN=""
if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python3"
elif [ -x /share/CACHEDEV1_DATA/.qpkg/Qsirch/bin/python3 ]; then
    PYTHON_BIN="/share/CACHEDEV1_DATA/.qpkg/Qsirch/bin/python3"
elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="python"
fi

while true; do
    RESPONSE="$(curl -s -X GET "${BRIDGE_URL}/api/v1/conversations/pending" \
        -H "Authorization: Bearer ${DEVICE_TOKEN}")" || true

    if [ -n "$RESPONSE" ] && [ -n "$PYTHON_BIN" ]; then
        echo "$RESPONSE" | "$PYTHON_BIN" -c '
import sys, json

try:
    content = sys.stdin.read().strip()
    if content:
        data = json.loads(content)
        if isinstance(data, list):
            for item in data:
                if isinstance(item, dict):
                    line = {
                        "id": item.get("id"),
                        "text": item.get("text"),
                        "session_id": item.get("session_id"),
                        "created_at": item.get("created_at")
                    }
                    print(json.dumps(line, ensure_ascii=False))
except Exception:
    pass
'
    fi

    if [ "${ONCE:-0}" = "1" ]; then
        break
    fi

    sleep "$INTERVAL"
done
