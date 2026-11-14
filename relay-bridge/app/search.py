import logging
import re
import time
from typing import Any
import httpx
from app.config import settings

logger = logging.getLogger(__name__)

# Categories that MUST NOT trigger search
BOARD_SKIP_PATTERNS = [
    r"作业",
    r"打卡",
    r"看板",
    r"今日安排",
    r"日程",
    r"通知公告",
    r"打卡清单",
    r"备忘",
]

DEVICE_SKIP_PATTERNS = [
    r"音量",
    r"调大音量",
    r"调小音量",
    r"静音",
    r"停止播放",
    r"暂停播放",
    r"继续播放",
    r"关机",
    r"重启",
    r"屏幕亮度",
    r"打开屏幕",
    r"关闭屏幕",
]

COMMON_KNOWLEDGE_SKIP_PATTERNS = [
    r"怎么翻译",
    r"英语怎么说",
    r"英文怎么说",
    r"中文怎么说",
    r"背诵",
    r"唐诗",
    r"宋词",
    r"古诗",
    r"李白",
    r"杜甫",
    r"静夜思",
]

MATH_SKIP_PATTERNS = [
    r"\d+\s*[\+\-\*/加减乘除]\s*\d+",
    r"等于几",
    r"等于多少",
]

MEMORY_SKIP_PATTERNS = [
    r"我的名字",
    r"我叫什么",
    r"我住在",
    r"我的生日",
    r"我多大",
    r"我喜欢什么",
]

# Time-sensitive trigger patterns
WEATHER_PATTERNS = [
    r"天气",
    r"气温",
    r"下雨",
    r"降雨",
    r"台风",
    r"空气质量",
    r"穿衣指数",
    r"下雪",
    r"降水",
    r"晴天",
    r"阴天",
    r"多少度",
    r"温度",
]

NEWS_PATTERNS = [
    r"新闻",
    r"最新",
    r"头条",
    r"热点",
    r"资讯",
    r"快讯",
    r"今日要闻",
    r"发生了什么",
]

REALTIME_DATA_PATTERNS = [
    r"汇率",
    r"金价",
    r"黄金价格",
    r"油价",
    r"汽油",
    r"股价",
    r"股票",
    r"大盘",
    r"指数",
    r"比赛比分",
    r"比分",
    r"赛程",
    r"票房",
]

EXPLICIT_SEARCH_PATTERNS = [
    r"查一下",
    r"帮我查",
    r"搜索",
    r"搜一下",
    r"上网查",
    r"帮我搜",
    r"检索",
]

SEARCH_DEGRADE_INSTRUCTION = (
    "当前联网搜索暂不可用，请根据已知常识回答；若涉及强时效问题无法确定，请直接礼貌说明暂未查到最新信息，切勿编造。"
)


def is_time_sensitive_query(query: str | None) -> bool:
    """Return True if query should trigger external web search.
    Follows: board skip, smalltalk skip, common knowledge skip, time-sensitive hit.
    """
    if not query or not query.strip():
        return False

    q = query.strip()

    # 1. Skip board data
    for pattern in BOARD_SKIP_PATTERNS:
        if re.search(pattern, q):
            return False

    # 2. Skip device control
    for pattern in DEVICE_SKIP_PATTERNS:
        if re.search(pattern, q):
            return False

    # 3. Skip common knowledge, math, and personal memory
    for pattern in COMMON_KNOWLEDGE_SKIP_PATTERNS:
        if re.search(pattern, q):
            return False
    for pattern in MATH_SKIP_PATTERNS:
        if re.search(pattern, q):
            return False
    for pattern in MEMORY_SKIP_PATTERNS:
        if re.search(pattern, q):
            return False

    # 4. Check time-sensitive triggers
    for group in (WEATHER_PATTERNS, NEWS_PATTERNS, REALTIME_DATA_PATTERNS, EXPLICIT_SEARCH_PATTERNS):
        for pattern in group:
            if re.search(pattern, q):
                return True

    return False


def clean_tts_text(text: str | None) -> str:
    """Sanitize text for TTS: strips markdown, markdown links, and raw URLs."""
    if not text:
        return ""
    # Strip markdown links, keeping label: [label](http...) -> label
    t = re.sub(r"\[([^\]]+)\]\(https?://[^\)]+\)", r"\1", text)
    # Strip angle-bracket wrapped URLs or HTML tags
    t = re.sub(r"<https?://[^>]+>", "", t)
    t = re.sub(r"<[^>]+>", "", t)
    # Strip standalone URLs
    t = re.sub(r"https?://\S+", "", t)
    t = re.sub(r"www\.\S+", "", t)
    # Strip markdown emphasis, headings, stray brackets
    t = re.sub(r"[*#`_~<>]", "", t)
    # Normalize whitespace
    t = re.sub(r"\s+", " ", t)
    return t.strip()


def extract_source_name(title: str | None, url: str | None = None) -> str:
    """Extract clean source/institution name for TTS speech."""
    raw = (title or "").strip()
    if not raw and url:
        m = re.search(r"https?://(?:www\.)?([^/:]+)", url)
        if m:
            raw = m.group(1)
        else:
            raw = "网络来源"

    # Separate title prefixes before common separators
    for sep in [" - ", " | ", " — ", "_", "-", "|"]:
        if sep in raw:
            parts = [p.strip() for p in raw.split(sep) if p.strip()]
            if parts:
                raw = parts[0]
            break

    cleaned = clean_tts_text(raw)
    # Strip bracket characters at borders
    cleaned = re.sub(r"^[\s\[\(（【]+|[\s\]\)）】]+$", "", cleaned)
    if len(cleaned) > 25:
        cleaned = cleaned[:25].strip()
    return cleaned or "网络来源"


def format_search_results(results: list[dict[str, Any]], max_results: int = 3) -> str:
    """Format search results according to Section 3.2.3 prompt template.
    Max 3 results, each max 150 chars, no URLs.
    """
    if not results:
        return ""

    items: list[str] = []
    for idx, item in enumerate(results[:max_results], 1):
        raw_title = item.get("title", "")
        raw_url = item.get("url", "")
        raw_content = item.get("content", "")

        source = extract_source_name(raw_title, raw_url)
        content = clean_tts_text(raw_content)

        if len(content) > 150:
            content = content[:150].rstrip() + "..."

        items.append(f"{idx}. [{source}]：{content}")

    results_text = "\n".join(items)
    return (
        "【联网搜索最新信息】\n"
        f"{results_text}\n\n"
        "【回答要求】\n"
        "请根据以上最新搜索结果回答。由于回答将直接语音朗读，严禁输出任何网址链接、英文标点或 Markdown 格式。引用来源时仅需口语化提及机构名（例如“根据气象台最新消息”）。"
    )


class TavilySearchClient:
    def __init__(
        self,
        api_key: str | None = None,
        api_url: str | None = None,
        timeout: float | None = None,
        max_results: int | None = None,
        hourly_limit: int | None = None,
    ) -> None:
        self.api_key = api_key if api_key is not None else settings.tavily_api_key
        self.api_url = api_url or settings.tavily_api_url
        self.timeout = timeout if timeout is not None else settings.search_timeout
        self.max_results = max_results if max_results is not None else settings.search_max_results
        self.hourly_limit = hourly_limit if hourly_limit is not None else settings.search_hourly_limit
        self._request_timestamps: list[float] = []

    def _check_and_record_rate_limit(self) -> bool:
        """Sliding window hourly rate limit. Returns True if request is allowed, False if exceeded."""
        now = time.time()
        one_hour_ago = now - 3600.0
        self._request_timestamps = [t for t in self._request_timestamps if t > one_hour_ago]
        if len(self._request_timestamps) >= self.hourly_limit:
            return False
        self._request_timestamps.append(now)
        return True

    async def search(self, query: str) -> list[dict[str, Any]]:
        """Call Tavily search API. Returns list of result dicts, or empty list on any failure."""
        if not self.api_key:
            logger.warning("Tavily search skipped: TAVILY_API_KEY is not configured")
            return []

        if not self._check_and_record_rate_limit():
            logger.warning("Tavily search skipped: hourly request limit (%d) reached", self.hourly_limit)
            return []

        payload = {
            "api_key": self.api_key,
            "query": query,
            "search_depth": "basic",
            "max_results": self.max_results,
            "include_answer": False,
        }
        headers = {
            "Content-Type": "application/json",
        }

        try:
            async with httpx.AsyncClient(timeout=self.timeout, trust_env=False) as client:
                resp = await client.post(self.api_url, json=payload, headers=headers)
                resp.raise_for_status()
                data = resp.json()
                results = data.get("results")
                if isinstance(results, list):
                    return results
                logger.warning("Tavily search returned unexpected response format: %s", data)
                return []
        except httpx.TimeoutException as exc:
            logger.warning("Tavily search timed out after %.1fs: %s", self.timeout, exc)
            return []
        except httpx.HTTPStatusError as exc:
            logger.warning("Tavily search returned HTTP %d: %s", exc.response.status_code, exc.response.text[:200])
            return []
        except httpx.RequestError as exc:
            logger.warning("Tavily search network request error: %s", exc)
            return []
        except Exception as exc:
            logger.warning("Tavily search unexpected error: %s", exc)
            return []

    async def search_for_chat(self, query: str) -> str | None:
        """Full pipeline for chat prompt injection:
        - If search not enabled or not time-sensitive: return None
        - If search succeeds with results: return formatted TTS prompt block
        - If search fails / degrades: return degrade instruction prompt
        Never raises exceptions and never causes 500/502.
        """
        if not settings.search_enabled:
            return None

        if not is_time_sensitive_query(query):
            return None

        results = await self.search(query)
        if results:
            formatted = format_search_results(results, max_results=self.max_results)
            if formatted:
                return formatted

        # Silent degrade instruction
        logger.warning("Degrading search for query '%s'", query[:50])
        return f"【说明】{SEARCH_DEGRADE_INSTRUCTION}"


search_client = TavilySearchClient()
