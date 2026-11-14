# Voice Kiosk Relay Bridge - Release & Deployment Guide

This document describes the environment configuration checklist, deployment verification, and persistence architecture for deploying `relay-bridge` on Docker (e.g. NAS or server host).

---

## 1. Environment Completeness Checklist

Before launching the container, create a production `.env` file in `relay-bridge/` based on `relay-bridge/example.env`. Verify that all mandatory variables are populated.

### 1.1 Variable Checklist

| Environment Variable | Status | Default | Description |
| :--- | :---: | :---: | :--- |
| `LLM_BASE_URL` | **REQUIRED** | *(none)* | Base URL for OpenAI-compatible LLM endpoint (e.g. `http://<host>:<port>`). |
| `LLM_MODEL` | **REQUIRED** | *(none)* | Model identifier to request for bridge fast-answer completions. |
| `LLM_API_KEY` | Optional | `""` | API key for upstream LLM endpoint (if required by upstream proxy). |
| `TAVILY_API_KEY` | Optional | `""` | API key for Tavily Web Search. If omitted, search degrades gracefully. |
| `DEVICE_TOKEN` | Recommended | `""` | Bearer token for Android kiosk device authentication. |
| `PUSH_TOKEN` | Recommended | `""` | Bearer token for push and conversation reply endpoints. |
| `PORT` | Optional | `8093` | Container listening port. |
| `TZ` | Optional | `Asia/Shanghai` | System timezone for logs, board timestamps, and scheduled tasks. |
| `DATA_DIR` | Optional | `/app/data` | Container path for persistent database and file storage. |
| `SEARCH_ENABLED` | Optional | `true` | Whether real-time web search grounding is enabled. |
| `SEARCH_TIMEOUT` | Optional | `2.5` | Timeout in seconds for Tavily search requests before silent degradation. |
| `SEARCH_MAX_RESULTS` | Optional | `3` | Max web search snippets injected into the system prompt. |
| `SEARCH_HOURLY_LIMIT` | Optional | `60` | Sliding window rate limit per hour to safeguard Tavily quota. |
| `MEMORY_ENABLED` | Optional | `true` | Whether long-term memory retrieval and prompt injection are enabled. |
| `MEMORY_DB_PATH` | Optional | `/app/data/memory.db` | Explicit SQLite database path. |

### 1.2 Fail-Fast Behavior Verification

The bridge implements a strict fail-fast policy at startup:
- If `LLM_BASE_URL` is missing, unset, or empty, `Settings.__init__()` raises `RuntimeError("LLM_BASE_URL environment variable is required")` immediately.
- If `LLM_MODEL` is missing, unset, or empty, `Settings.__init__()` raises `RuntimeError("LLM_MODEL environment variable is required")` immediately.
- **No silent fallbacks**: The application refuses to start with undefined models or URLs, preventing unintended fallback to obsolete or hardcoded endpoints.

---

## 2. Long-Term Memory & Data Persistence

### 2.1 Volume Mount Architecture

The `relay-bridge/docker-compose.yml` mounts host directory `./data` to container path `/app/data`:

```yaml
services:
  relay-bridge:
    build: .
    container_name: relay-bridge
    restart: unless-stopped
    ports:
      - "8093:8093"
    environment:
      - DATA_DIR=/app/data
      - PORT=8093
    env_file:
      - path: .env
        required: false
    volumes:
      - ./data:/app/data
```

### 2.2 Preserved Assets Across Container Rebuilds

All persistent state is stored strictly inside `DATA_DIR`:
1. `memory.db`: SQLite database holding:
   - `memories`: Active long-term memories (WAL mode).
   - `memory_tombstones`: Deletion tombstones for incremental synchronization with Android clients.
2. `boards/`: JSON documents for dashboard notices, schedules, and chore lists.
3. `conversations/`: Enqueued long-running conversation tasks.

Because `./data` is a mounted host volume:
- Container stops, removals, image rebuilds (`docker compose down && docker compose up --build -d`), and system reboots **do not lose any memory data or board state**.
- SQLite WAL journal mode (`PRAGMA journal_mode=WAL;`) ensures ACID compliance and fast concurrent read/write performance.

---

## 3. Web Search Graceful Degradation

- When users ask time-sensitive questions (e.g. weather, live news, real-time data), the bridge queries Tavily API within a strict `2.5` second budget (`SEARCH_TIMEOUT`).
- If `TAVILY_API_KEY` is not provided, or if the search times out or errors, the bridge logs a warning and injects a silent degrade instruction into the system prompt:
  ```text
  【说明】当前联网搜索暂不可用，请根据已知常识回答；若涉及强时效问题无法确定，请直接礼貌说明暂未查到最新信息，切勿编造。
  ```
- The fast-answer endpoint (`POST /api/v1/chat`) guarantees it will never return HTTP 500 or 502 due to web search failures.

---

## 4. Pre-Deployment Verification Checklist

Before deploying changes to production:

1. **Verify No Hardcoded Literals in Source Code**:
   Verify that no hardcoded IP addresses, custom ports, internal domains, or specific model names appear anywhere in `relay-bridge/app/`:
   ```bash
   # Search for accidental hardcoded IPs or model identifiers (example pattern):
   grep -rn --exclude-dir=__pycache__ -E "[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}" relay-bridge/app/
   ```
   *(Must return 0 matches).*

2. **Run Full Test Suite**:
   ```bash
   pytest relay-bridge/tests
   ```
   *(All tests must pass using isolated temporary databases).*

3. **Verify Fail-Fast Startup**:
   Test that unsetting `LLM_MODEL` prevents container startup with a clear error:
   ```bash
   python3 -c "import os; os.environ.pop('LLM_MODEL', None); from app.config import Settings; Settings()"
   ```
   *(Must raise `RuntimeError: LLM_MODEL environment variable is required`).*

4. **Deploy & Cold Restart Verification on NAS**:
   ```bash
   # Rebuild and start container
   docker compose up -d --build

   # Verify health endpoint
   curl -s http://localhost:8093/health

   # Cold restart container to verify persistence
   docker compose restart
   ```
