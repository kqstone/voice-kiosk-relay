from typing import Any
import httpx
from app.config import settings
from app.storage import BoardStorage


class BridgeLLMError(Exception):
    """Raised when an error occurs during Bridge LLM calls."""
    pass


class BridgeLLMClient:
    def __init__(
        self,
        base_url: str | None = None,
        api_key: str | None = None,
        model: str | None = None,
        timeout: float = 30.0,
    ) -> None:
        self.base_url = (base_url or settings.llm_base_url or "http://100.68.168.204:8317").rstrip("/")
        self.api_key = api_key if api_key is not None else settings.llm_api_key
        self.model = model or settings.llm_model or "gemini-3.5-flash-lite"
        self.timeout = timeout

    @property
    def endpoint_url(self) -> str:
        # Request URL must be the base URL plus /v1/chat/completions.
        # Ensure /v1 path is always present without duplicating it.
        base = self.base_url.rstrip("/")
        if base.endswith("/v1"):
            return f"{base}/chat/completions"
        return f"{base}/v1/chat/completions"

    async def chat(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.7,
    ) -> str:
        if not self.api_key:
            raise BridgeLLMError("LLM API key is not configured")

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
        }

        try:
            async with httpx.AsyncClient(timeout=self.timeout, trust_env=False) as client:
                response = await client.post(
                    self.endpoint_url,
                    json=payload,
                    headers=headers,
                )
                response.raise_for_status()
                data = response.json()
        except httpx.HTTPStatusError as exc:
            status_code = exc.response.status_code
            err_text = exc.response.text[:200]
            raise BridgeLLMError(f"LLM API returned status {status_code}: {err_text}") from exc
        except httpx.RequestError as exc:
            raise BridgeLLMError(f"LLM network error: {exc}") from exc
        except Exception as exc:
            raise BridgeLLMError(f"LLM unexpected error: {exc}") from exc

        choices = data.get("choices")
        if not isinstance(choices, list) or len(choices) == 0:
            raise BridgeLLMError(f"LLM returned no choices: {data}")

        first_choice = choices[0]
        message = first_choice.get("message", {})
        content = message.get("content")
        if not isinstance(content, str) or not content.strip():
            raise BridgeLLMError("LLM returned empty message content")

        return content.strip()


def format_board_summary(storage: BoardStorage) -> str:
    board_ids = storage.list_boards()
    if not board_ids:
        return "当前无看板数据。"

    # Sort boards by recency: newest updated_at (or timestamp fallback) first;
    # boards with no usable timestamp sort last.
    def _board_ts(bid: str) -> str:
        board = storage.get_board(bid)
        if not board:
            return ""
        return board.get("updated_at") or board.get("timestamp") or ""

    with_ts = [bid for bid in board_ids if _board_ts(bid)]
    without_ts = [bid for bid in board_ids if not _board_ts(bid)]
    with_ts.sort(key=_board_ts, reverse=True)  # newest ISO-8601 string first
    sorted_ids = with_ts + without_ts

    summaries: list[str] = []
    for bid in sorted_ids:
        board = storage.get_board(bid)
        if not board:
            continue
        data = board.get("data")
        updated_at = board.get("updated_at") or board.get("timestamp") or "未知时间"

        lines = [f"看板【{bid}】 (更新时间: {updated_at}):"]
        if isinstance(data, dict):
            title = data.get("title")
            if title:
                lines.append(f"标题: {title}")
            date = data.get("date")
            if date:
                lines.append(f"日期: {date}")
            items = data.get("items")
            if isinstance(items, list):
                lines.append("内容列表:")
                for idx, item in enumerate(items, 1):
                    if isinstance(item, dict):
                        item_text = item.get("text") or item.get("title") or item.get("content") or str(item)
                        status_str = " (已完成)" if (item.get("completed") or item.get("done") or item.get("checked")) else ""
                        lines.append(f"  {idx}. {item_text}{status_str}")
                    else:
                        lines.append(f"  {idx}. {item}")
            for k, v in data.items():
                if k not in ("title", "date", "items"):
                    lines.append(f"{k}: {v}")
        elif isinstance(data, list):
            lines.append("内容列表:")
            for idx, item in enumerate(data, 1):
                lines.append(f"  {idx}. {item}")
        else:
            lines.append(str(data))
        summaries.append("\n".join(lines))

    return "\n\n".join(summaries)


def build_chat_system_prompt(boards_summary: str) -> str:
    return (
        "你是家庭智能语音助手 Voice Kiosk。\n"
        "你的任务是为家庭成员提供自然、亲切、简洁、准确的回答。\n"
        "回答内容将直接通过语音合成（TTS）朗读给用户，因此请使用口语化、简洁的中文，不要使用 markdown 标记或复杂列表符号。\n\n"
        "【实时看板信息】\n"
        f"{boards_summary}\n\n"
        "【回答规则】\n"
        "1. 如果用户询问看板相关信息（例如今日安排、最新通知等看板内容），请务必根据上述看板内容准确回答。\n"
        "2. 如果用户进行日常问候或常识问答，请简明扼要地回答。"
    )


llm_client = BridgeLLMClient()
