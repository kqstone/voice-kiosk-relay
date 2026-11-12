from typing import Any
import json

from app.llm import BridgeLLMClient, llm_client

# Override phrase lists for immediate routing without LLM judgment
OVERRIDE_LOCAL_PHRASES: list[str] = [
    "do not ask Muse",
    "do not ask muse",
    "answer directly",
    "不用问 Muse",
    "不用问 muse",
    "不用问Muse",
    "不用问muse",
    "直接答",
]

OVERRIDE_MUSE_PHRASES: list[str] = [
    "ask Muse",
    "ask muse",
    "go ask Muse",
    "go ask muse",
    "问 Muse",
    "问 muse",
    "问问 Muse",
    "问问 muse",
    "去问 Muse",
    "去问 muse",
]

# In-memory correction store for exact question matches
_CORRECTIONS: dict[str, str] = {}


def record_correction(question: str, forced_route: str) -> None:
    cleaned_route = forced_route.strip().lower()
    _CORRECTIONS[question] = cleaned_route
    _CORRECTIONS[question.strip()] = cleaned_route


def lookup_correction(question: str) -> str | None:
    if question in _CORRECTIONS:
        return _CORRECTIONS[question]
    return _CORRECTIONS.get(question.strip())


def clear_corrections() -> None:
    _CORRECTIONS.clear()


def check_explicit_override(question: str) -> str | None:
    # Check local override first so negative commands take precedence
    for phrase in OVERRIDE_LOCAL_PHRASES:
        if phrase in question:
            return "local"
    for phrase in OVERRIDE_MUSE_PHRASES:
        if phrase in question:
            return "muse"
    return None


ROUTER_SYSTEM_PROMPT = (
    "你是一个智能语音助手的意图识别与路由中枢。\n"
    "你的任务是分析用户的输入问题，提取关键词并判断该问题应当路由给哪个通道（muse 或 local）。\n\n"
    "【路由判定策略】\n"
    "1. 路由至 muse（由高级智能体 Muse 深度处理）：\n"
    "   - 需要记忆或个人信息（如家庭成员习惯、偏好、个人档案、历史记录等）\n"
    "   - 需要调用外部工具（如查询外部网络、发送消息、执行打印、添加日程或提醒等）\n"
    "   - 需要多步骤综合处理的任务\n"
    "   - 需要对看板内容进行深度处理（如详细学习或任务规划、复杂内容深度分析与讲解、批改与纠错等）\n\n"
    "2. 路由至 local（由中继桥内置模型快答处理）：\n"
    "   - 简单问答、常识问答\n"
    "   - 天气与时间查询\n"
    "   - 日常闲聊打招呼\n"
    "   - 看板已有数据的查询（如查询今日安排、最新通知等看板展示内容）\n\n"
    "【关键词提取要求】\n"
    "从用户问题中提取核心关键词列表（包含 0 到 5 个关键词，对口语词汇进行归一化，例如明儿归一化为明天）。\n\n"
    "【返回格式规范】\n"
    "必须返回且仅返回一个合法的 JSON 对象，包含且仅包含以下三个字段：\n"
    "{\n"
    '  "keywords": ["关键词1", "关键词2"],\n'
    '  "route": "muse" 或 "local",\n'
    '  "reason": "简短判定理由"\n'
    "}\n"
    "禁止输出任何额外的 Markdown 代码块符号、思考过程或多余解释。"
)


def format_keyword_hints(keyword_config: dict[str, Any] | None) -> str:
    if not keyword_config or not isinstance(keyword_config, dict):
        return ""
    muse_keywords = keyword_config.get("muse_keywords")
    if not isinstance(muse_keywords, list) or not muse_keywords:
        return ""
    hints = ["【参考关键词配置】（仅作决策参考；若命中且需要深度处理倾向 muse，若仅为看板数据查询依然走 local）："]
    for item in muse_keywords:
        if isinstance(item, dict):
            kw = item.get("keyword")
            topic = item.get("topic")
            note = item.get("note")
            if kw:
                details = []
                if topic:
                    details.append(f"主题: {topic}")
                if note:
                    details.append(f"说明: {note}")
                if details:
                    hints.append(f"- {kw} ({', '.join(details)})")
                else:
                    hints.append(f"- {kw}")
        elif isinstance(item, str) and item:
            hints.append(f"- {item}")
    if len(hints) <= 1:
        return ""
    return "\n".join(hints)


def build_router_messages(
    question: str,
    keyword_config: dict[str, Any] | None = None,
) -> list[dict[str, str]]:
    hints = format_keyword_hints(keyword_config)
    user_prompt = f"用户问题：{question}"
    if hints:
        user_prompt = f"{hints}\n\n{user_prompt}"
    return [
        {"role": "system", "content": ROUTER_SYSTEM_PROMPT},
        {"role": "user", "content": user_prompt},
    ]


def parse_router_response(raw_text: str) -> dict[str, Any]:
    fallback_result: dict[str, Any] = {
        "keywords": [],
        "route": "local",
        "reason": "fallback",
    }
    if not raw_text or not isinstance(raw_text, str):
        return fallback_result

    cleaned = raw_text.strip()
    fence = chr(96) * 3
    if cleaned.startswith(fence):
        lines = cleaned.splitlines()
        if len(lines) >= 2:
            if lines[0].startswith(fence):
                lines = lines[1:]
            if lines and lines[-1].strip().startswith(fence):
                lines = lines[:-1]
            cleaned = "\n".join(lines).strip()

    try:
        data = json.loads(cleaned)
    except Exception:
        return fallback_result

    if not isinstance(data, dict):
        return fallback_result

    route = data.get("route")
    if not isinstance(route, str):
        return fallback_result

    route_normalized = route.strip().lower()
    if route_normalized not in ("muse", "local"):
        return fallback_result

    raw_keywords = data.get("keywords")
    if isinstance(raw_keywords, list):
        keywords = [str(k) for k in raw_keywords if k is not None]
    else:
        keywords = []

    reason = data.get("reason")
    if isinstance(reason, str) and reason.strip():
        clean_reason = reason.strip()
    else:
        clean_reason = "LLM routed"

    return {
        "keywords": keywords,
        "route": route_normalized,
        "reason": clean_reason,
    }


async def route_question(
    question: str,
    keyword_config: dict[str, Any] | None = None,
    client: BridgeLLMClient | None = None,
) -> dict[str, Any]:
    # 1. Exact match in correction store
    forced = lookup_correction(question)
    if forced is not None:
        return {
            "keywords": [],
            "route": forced,
            "reason": f"correction: {forced}",
        }

    # 2. Explicit override in raw question text
    override = check_explicit_override(question)
    if override is not None:
        return {
            "keywords": [],
            "route": override,
            "reason": f"explicit override: {override}",
        }

    # 3. Handle empty/whitespace question
    clean_question = question.strip() if question else ""
    if not clean_question:
        return {
            "keywords": [],
            "route": "local",
            "reason": "fallback",
        }

    # 4. Invoke LLM for keyword extraction and route judgment
    llm = client or llm_client
    messages = build_router_messages(clean_question, keyword_config)

    try:
        raw_output = await llm.chat(messages=messages, temperature=0.1)
    except Exception:
        return {
            "keywords": [],
            "route": "local",
            "reason": "fallback",
        }

    # 5. Parse and strictly validate the JSON output
    return parse_router_response(raw_output)
