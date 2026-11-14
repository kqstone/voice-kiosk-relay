from datetime import datetime, timezone
import html
import json
from typing import Any

PALETTE: list[str] = [
    "#06B6D4",  # Cyan
    "#8B5CF6",  # Purple
    "#10B981",  # Emerald
    "#F59E0B",  # Amber
    "#EC4899",  # Pink
    "#3B82F6",  # Blue
]


def _get_color_for_label(label: str, index: int) -> str:
    if label:
        return PALETTE[abs(hash(label)) % len(PALETTE)]
    return PALETTE[index % len(PALETTE)]


def _extract_board_title(board_id: str, data: Any) -> str:
    if isinstance(data, dict):
        if data.get("title"):
            return str(data["title"])
        if data.get("name"):
            return str(data["name"])
    return board_id


def _extract_board_date(board: dict[str, Any]) -> str:
    data = board.get("data")
    if isinstance(data, dict) and data.get("date"):
        return str(data["date"])
    updated_at = board.get("updated_at") or board.get("timestamp") or ""
    if updated_at:
        return str(updated_at)[:10]
    return ""


def _extract_board_items(data: Any) -> list[str]:
    if isinstance(data, list):
        return [str(x) for x in data]
    if isinstance(data, dict):
        for key in ["items", "tasks", "checklist", "todo", "content", "list"]:
            val = data.get(key)
            if isinstance(val, list):
                res: list[str] = []
                for item in val:
                    if isinstance(item, dict):
                        text = item.get("text") or item.get("title") or item.get("content") or json.dumps(item, ensure_ascii=False)
                        res.append(str(text))
                    else:
                        res.append(str(item))
                return res
            if isinstance(val, str):
                return [val]
        pairs: list[str] = []
        for k, v in data.items():
            if k in ["title", "date", "source", "version", "tag", "category", "subtitle", "description", "desc", "badge"]:
                continue
            pairs.append(f"{k}: {v}")
        if pairs:
            return pairs
    if isinstance(data, str):
        return [data]
    return []


def parse_board_to_components(board_id: str, raw_board: Any) -> list[dict[str, Any]]:
    """Generic board component parser.

    Produces Title, Checklist, List, and KeyValue components.
    Zero domain names, subject names, person names, or board IDs hardcoded.
    Checklist items do NOT include estimated-time or meta fields.
    """
    components: list[dict[str, Any]] = []

    if isinstance(raw_board, dict) and ("data" in raw_board or "board_id" in raw_board):
        b_id = str(raw_board.get("board_id") or board_id)
        data = raw_board.get("data", raw_board)
    else:
        b_id = board_id
        data = raw_board

    title = ""
    tag = ""
    subtitle = ""
    explicit_badge = ""

    if isinstance(data, dict):
        title = str(data.get("title") or data.get("name") or b_id)
        tag = str(data.get("tag") or data.get("category") or data.get("group") or "")
        subtitle = str(data.get("subtitle") or data.get("description") or data.get("desc") or "")
        explicit_badge = str(data.get("badge") or "")
    else:
        title = b_id

    # If data explicitly defines structured components, return them directly
    if isinstance(data, dict) and isinstance(data.get("components"), list):
        return data["components"]

    raw_items: list[Any] | None = None
    list_key = ""
    if isinstance(data, dict):
        for candidate_key in ["tasks", "items", "checklist", "todo", "content", "list"]:
            val = data.get(candidate_key)
            if isinstance(val, list):
                raw_items = val
                list_key = candidate_key
                break
    elif isinstance(data, list):
        raw_items = data

    if raw_items is not None:
        explicit_type = data.get("type") if isinstance(data, dict) else None
        is_explicit_list = explicit_type == "list" or (list_key == "list" and not any(
            isinstance(it, dict) and any(k in it for k in ["completed", "done", "checked"])
            for it in raw_items
        ))

        if is_explicit_list:
            list_items: list[dict[str, Any]] = []
            for it in raw_items:
                if isinstance(it, dict):
                    t = str(it.get("text") or it.get("title") or it.get("content") or json.dumps(it, ensure_ascii=False))
                else:
                    t = str(it)
                list_items.append({"text": t})

            badge = explicit_badge or (f"{len(list_items)} 项" if list_items else "")
            components.append({
                "type": "title",
                "title": title,
                "tag": tag,
                "subtitle": subtitle,
                "badge": badge,
            })
            components.append({
                "type": "list",
                "items": list_items,
            })
        else:
            checklist_items: list[dict[str, Any]] = []
            for idx, item in enumerate(raw_items):
                if isinstance(item, dict):
                    item_id = str(item.get("id") or f"item_{idx}")
                    label = str(item.get("label") or item.get("subject") or item.get("tag") or item.get("category") or "")
                    text = str(item.get("text") or item.get("title") or item.get("content") or item.get("name") or "")
                    is_done = bool(item.get("completed") or item.get("done") or item.get("checked"))
                    color = str(item.get("color") or item.get("color_hint") or _get_color_for_label(label, idx))
                else:
                    item_id = f"item_{idx}"
                    label = ""
                    text = str(item)
                    is_done = False
                    color = _get_color_for_label("", idx)

                checklist_items.append({
                    "id": item_id,
                    "label": label,
                    "text": text,
                    "completed": is_done,
                    "color_hint": color,
                })

            completed_count = sum(1 for it in checklist_items if it["completed"])
            total_count = len(checklist_items)
            badge = explicit_badge or (f"{completed_count} / {total_count} 完成" if total_count > 0 else "")

            components.append({
                "type": "title",
                "title": title,
                "tag": tag,
                "subtitle": subtitle,
                "badge": badge,
            })
            components.append({
                "type": "checklist",
                "items": checklist_items,
            })
    elif isinstance(data, dict):
        pairs: list[dict[str, str]] = []
        ignored_keys = {
            "title", "name", "tag", "category", "group", "subtitle",
            "description", "desc", "badge", "date", "source", "updated_at",
            "timestamp", "version", "type"
        }
        for k, v in data.items():
            if k not in ignored_keys:
                pairs.append({"key": str(k), "value": str(v)})

        badge = explicit_badge or (f"{len(pairs)} 项" if pairs else "")
        components.append({
            "type": "title",
            "title": title,
            "tag": tag,
            "subtitle": subtitle,
            "badge": badge,
        })
        if pairs:
            components.append({
                "type": "key_value",
                "pairs": pairs,
            })
    else:
        components.append({
            "type": "title",
            "title": title,
            "tag": tag,
            "subtitle": subtitle,
            "badge": explicit_badge,
        })
        if data:
            components.append({
                "type": "list",
                "items": [{"text": str(data)}],
            })

    return components


def render_dashboard(
    boards: list[dict[str, Any]],
    memories: list[dict[str, Any]] | None = None,
) -> str:
    """Renders OLED true dark HTML dashboard.

    - Pure black background (#000000)
    - Dark cards (#141416) with subtle borders (#27272A)
    - Strikethrough text and purple checked circles for completed items
    - Each board as its own distinct section
    - Long-term memory management card
    """
    rendered_sections: list[str] = []

    if not boards:
        rendered_sections.append(
            '<div class="empty-state" id="empty-state">暂无看板数据</div>'
        )
    else:
        for b in boards:
            b_id = html.escape(str(b.get("board_id", "")))
            date_str = html.escape(_extract_board_date(b))
            source_str = html.escape(str(b.get("source", "muse")))

            components = parse_board_to_components(b_id, b)

            title_comp: dict[str, Any] = {}
            body_parts: list[str] = []

            for comp in components:
                c_type = comp.get("type")
                if c_type == "title":
                    title_comp = comp
                elif c_type == "checklist":
                    items = comp.get("items", [])
                    if items:
                        body_parts.append('<div class="task-grid">')
                        for it in items:
                            it_id = html.escape(str(it.get("id", "")))
                            it_label = html.escape(str(it.get("label", "")))
                            it_text = html.escape(str(it.get("text", "")))
                            it_done = bool(it.get("completed", False))
                            it_color = html.escape(str(it.get("color_hint", "#8B5CF6")))

                            completed_class = " completed" if it_done else ""
                            checked_class = " checked" if it_done else ""
                            check_icon = "✓" if it_done else ""

                            label_html = ""
                            if it_label:
                                label_html = '<span class="task-label">' + it_label + '</span>'

                            body_parts.append(
                                '<div class="task-card' + completed_class + '" data-board-id="' + b_id + '" data-item-id="' + it_id + '" onclick="toggleItem(\'' + b_id + '\', \'' + it_id + '\')">'
                                + '<div class="task-left">'
                                + '<span class="task-dot" style="background-color: ' + it_color + ';"></span>'
                                + label_html
                                + '<span class="task-text">' + it_text + '</span>'
                                + '</div>'
                                + '<div class="task-checkbox' + checked_class + '">' + check_icon + '</div>'
                                + '</div>'
                            )
                        body_parts.append('</div>')
                    else:
                        body_parts.append('<p class="item-empty">（暂无清单项）</p>')

                elif c_type == "list":
                    items = comp.get("items", [])
                    if items:
                        body_parts.append('<ul class="generic-list">')
                        for it in items:
                            txt = html.escape(str(it.get("text", "")))
                            body_parts.append(
                                '<li class="generic-list-item">'
                                + '<span class="list-bullet"></span>'
                                + '<span class="list-text">' + txt + '</span>'
                                + '</li>'
                            )
                        body_parts.append('</ul>')
                    else:
                        body_parts.append('<p class="item-empty">（暂无列表内容）</p>')

                elif c_type == "key_value":
                    pairs = comp.get("pairs", [])
                    if pairs:
                        body_parts.append('<div class="kv-grid">')
                        for p in pairs:
                            k = html.escape(str(p.get("key", "")))
                            v = html.escape(str(p.get("value", "")))
                            body_parts.append(
                                '<div class="kv-row">'
                                + '<span class="kv-key">' + k + '</span>'
                                + '<span class="kv-value">' + v + '</span>'
                                + '</div>'
                            )
                        body_parts.append('</div>')

            comp_title = html.escape(str(title_comp.get("title") or b_id))
            comp_tag = html.escape(str(title_comp.get("tag") or ""))
            comp_subtitle = html.escape(str(title_comp.get("subtitle") or ""))
            comp_badge = html.escape(str(title_comp.get("badge") or ""))

            tag_html = ('<span class="board-tag">' + comp_tag + '</span>') if comp_tag else ""
            subtitle_html = ('<p class="board-subtitle">' + comp_subtitle + '</p>') if comp_subtitle else ""
            badge_html = ('<span class="board-badge">' + comp_badge + '</span>') if comp_badge else ""
            date_html = ('<span class="meta-date">' + date_str + '</span>') if date_str else ""

            section_html = (
                '<section class="board-section" id="board-' + b_id + '" data-board-id="' + b_id + '">'
                + '<div class="board-header">'
                + '<div class="header-left">'
                + '<div class="title-row">'
                + '<span class="indicator-dot"></span>'
                + '<h2 class="board-title">' + comp_title + '</h2>'
                + tag_html
                + '</div>'
                + subtitle_html
                + '<div class="board-meta">'
                + '<span class="meta-id">' + b_id + '</span>'
                + date_html
                + '<span class="meta-source">来源: ' + source_str + '</span>'
                + '</div>'
                + '</div>'
                + '<div class="header-right">'
                + badge_html
                + '</div>'
                + '</div>'
                + '<div class="board-body">'
                + "".join(body_parts)
                + '</div>'
                + '</section>'
            )
            rendered_sections.append(section_html)

    sections_block = "\n".join(rendered_sections)

    memory_rows: list[str] = []
    mem_list = memories or []
    if not mem_list:
        memory_rows.append('<tr><td colspan="6" class="item-empty">暂无长期记忆</td></tr>')
    else:
        for m in mem_list:
            m_id = html.escape(str(m.get("id", "")))
            m_cat = html.escape(str(m.get("category", "")))
            m_key = html.escape(str(m.get("key", "")))
            m_content = html.escape(str(m.get("content", "")))
            m_time = html.escape(str(m.get("updated_at", "")))
            try:
                ts = float(m.get("updated_at", 0))
                if ts > 0:
                    dt = datetime.fromtimestamp(ts, tz=timezone.utc)
                    m_time = dt.strftime("%Y-%m-%d %H:%M:%S")
            except Exception:
                pass

            memory_rows.append(
                f'<tr id="memory-row-{m_id}">'
                f'<td class="mem-td-id">{m_id}</td>'
                f'<td class="mem-td-cat"><span class="mem-badge-cat">{m_cat}</span></td>'
                f'<td class="mem-td-key">{m_key or "-"}</td>'
                f'<td class="mem-td-content">{m_content}</td>'
                f'<td class="mem-td-time">{m_time}</td>'
                f'<td class="mem-td-action"><button type="button" class="btn-delete-mem" onclick="deleteMemory({m_id})">删除</button></td>'
                f'</tr>'
            )

    memory_section_html = (
        '<section class="board-section memory-section" id="memory-management-card">'
        + '<div class="board-header">'
        + '<div class="header-left">'
        + '<div class="title-row">'
        + '<span class="indicator-dot" style="background: #10B981; box-shadow: 0 0 8px rgba(16, 185, 129, 0.6);"></span>'
        + '<h2 class="board-title">长期记忆管理</h2>'
        + '<span class="board-tag" style="background: rgba(16, 185, 129, 0.15); color: #6ee7b7; border-color: rgba(16, 185, 129, 0.3);">Memory Store</span>'
        + '</div>'
        + '<p class="board-subtitle">家庭成员个性化偏好与事实记忆（零信任存储与增量同步）</p>'
        + '</div>'
        + '<div class="header-right">'
        + f'<span class="board-badge" id="memory-count-badge" style="background: #064e3b; color: #a7f3d0; border-color: rgba(16, 185, 129, 0.4);">{len(mem_list)} 条记忆</span>'
        + '</div>'
        + '</div>'
        + '<div class="board-body">'
        + '<div class="memory-table-wrapper" style="overflow-x: auto;">'
        + '<table class="memory-table">'
        + '<thead>'
        + '<tr>'
        + '<th style="width: 50px;">ID</th>'
        + '<th style="width: 90px;">分类</th>'
        + '<th style="width: 110px;">键名</th>'
        + '<th>记忆内容</th>'
        + '<th style="width: 160px;">时间</th>'
        + '<th style="width: 70px;">操作</th>'
        + '</tr>'
        + '</thead>'
        + '<tbody id="memory-table-body">'
        + "".join(memory_rows)
        + '</tbody>'
        + '</table>'
        + '</div>'
        + '</div>'
        + '</section>'
    )

    html_doc = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Voice Kiosk 看板</title>
  <style>
    :root {
      --bg-color: #000000;
      --card-bg: #141416;
      --card-inner: #18181b;
      --border-color: #27272a;
      --border-light: #3f3f46;
      --text-main: #f8fafc;
      --text-secondary: #94a3b8;
      --text-muted: #64748b;
      --accent-purple: #8b5cf6;
      --accent-purple-glow: rgba(139, 92, 246, 0.6);
      --badge-bg: #2e1065;
      --badge-text: #e0e7ff;
      --card-radius: 16px;
      --item-radius: 12px;
    }
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
      background-color: var(--bg-color);
      color: var(--text-main);
      line-height: 1.5;
      padding: 20px 16px;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
    }
    .container {
      width: 100%;
      max-width: 860px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    header.kiosk-nav {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 14px 20px;
      background: var(--card-bg);
      border-radius: var(--card-radius);
      border: 1px solid var(--border-color);
    }
    .nav-title-group {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .nav-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      background: var(--accent-purple);
      box-shadow: 0 0 10px var(--accent-purple-glow);
    }
    .nav-title {
      font-size: 18px;
      font-weight: 700;
      letter-spacing: -0.3px;
      color: var(--text-main);
    }
    .nav-badges {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .badge {
      font-size: 12px;
      padding: 4px 10px;
      border-radius: 9999px;
      font-weight: 500;
    }
    .badge-ws {
      background: #18181b;
      color: var(--text-secondary);
      border: 1px solid var(--border-color);
    }
    .badge-ws.connected {
      background: rgba(16, 185, 129, 0.15);
      color: #34d399;
      border-color: rgba(16, 185, 129, 0.4);
    }
    .badge-ws.error {
      background: rgba(239, 68, 68, 0.15);
      color: #f87171;
      border-color: rgba(239, 68, 68, 0.4);
    }
    .controls-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0 4px;
      font-size: 13px;
      color: var(--text-secondary);
    }
    .controls-bar a, .controls-bar button {
      color: var(--accent-purple);
      text-decoration: none;
      background: none;
      border: none;
      font-size: 13px;
      cursor: pointer;
    }
    .token-modal {
      display: none;
      padding: 16px;
      background: var(--card-bg);
      border-radius: var(--card-radius);
      border: 1px solid var(--border-color);
    }
    .token-modal input {
      width: 100%;
      padding: 10px 12px;
      font-size: 14px;
      background: var(--card-inner);
      color: var(--text-main);
      border: 1px solid var(--border-color);
      border-radius: 8px;
      margin-top: 8px;
      margin-bottom: 12px;
    }
    .token-modal input:focus {
      outline: none;
      border-color: var(--accent-purple);
    }
    .token-modal button {
      padding: 8px 18px;
      background: var(--accent-purple);
      color: #ffffff;
      border: none;
      border-radius: 8px;
      font-weight: 600;
      cursor: pointer;
    }
    .boards-wrapper {
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    .board-section {
      background: var(--card-bg);
      border-radius: var(--card-radius);
      border: 1px solid var(--border-color);
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .board-header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      padding-bottom: 12px;
      border-bottom: 1px solid var(--border-color);
    }
    .header-left {
      display: flex;
      flex-direction: column;
      gap: 6px;
      flex: 1;
    }
    .title-row {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }
    .indicator-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--accent-purple);
      box-shadow: 0 0 8px var(--accent-purple-glow);
      flex-shrink: 0;
    }
    .board-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      word-break: break-word;
    }
    .board-tag {
      font-size: 12px;
      padding: 2px 8px;
      border-radius: 9999px;
      background: rgba(139, 92, 246, 0.15);
      color: #c4b5fd;
      border: 1px solid rgba(139, 92, 246, 0.3);
      font-weight: 500;
    }
    .board-subtitle {
      font-size: 13px;
      color: var(--text-secondary);
      line-height: 1.4;
    }
    .board-meta {
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 12px;
      color: var(--text-muted);
      flex-wrap: wrap;
    }
    .meta-id {
      font-family: monospace;
      background: #18181b;
      padding: 2px 6px;
      border-radius: 4px;
      border: 1px solid var(--border-color);
    }
    .meta-date {
      color: var(--accent-purple);
      font-weight: 500;
    }
    .header-right {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 6px;
      flex-shrink: 0;
    }
    .board-badge {
      font-size: 13px;
      font-weight: 600;
      padding: 4px 12px;
      border-radius: 9999px;
      background: var(--badge-bg);
      color: var(--badge-text);
      border: 1px solid rgba(139, 92, 246, 0.4);
      white-space: nowrap;
    }
    .board-body {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .task-grid {
      display: grid;
      grid-template-columns: 1fr;
      gap: 10px;
    }
    @media (min-width: 680px) {
      .task-grid {
        grid-template-columns: repeat(2, 1fr);
      }
    }
    .task-card {
      background: var(--card-inner);
      border-radius: var(--item-radius);
      border: 1px solid var(--border-color);
      padding: 14px 16px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      cursor: pointer;
      transition: border-color 0.15s ease, background 0.15s ease;
      user-select: none;
    }
    .task-card:hover {
      border-color: var(--border-light);
    }
    .task-left {
      display: flex;
      align-items: center;
      gap: 10px;
      flex: 1;
      min-width: 0;
    }
    .task-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .task-label {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-secondary);
      flex-shrink: 0;
    }
    .task-text {
      font-size: 15px;
      color: var(--text-main);
      word-break: break-word;
      line-height: 1.4;
      flex: 1;
    }
    .task-checkbox {
      width: 22px;
      height: 22px;
      border-radius: 50%;
      border: 2px solid #475569;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      font-size: 13px;
      font-weight: 700;
      color: transparent;
      transition: all 0.2s ease;
    }
    .task-card.completed .task-text {
      color: var(--text-muted);
      text-decoration: line-through;
    }
    .task-card.completed .task-dot {
      opacity: 0.4;
    }
    .task-card.completed .task-label {
      opacity: 0.4;
    }
    .task-checkbox.checked {
      background: var(--accent-purple);
      border-color: var(--accent-purple);
      color: #ffffff;
    }
    .generic-list {
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .generic-list-item {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      padding: 10px 14px;
      background: var(--card-inner);
      border-radius: var(--item-radius);
      border: 1px solid var(--border-color);
      font-size: 15px;
      color: var(--text-main);
    }
    .list-bullet {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--accent-purple);
      margin-top: 8px;
      flex-shrink: 0;
    }
    .list-text {
      flex: 1;
      word-break: break-word;
    }
    .kv-grid {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .kv-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 14px;
      background: var(--card-inner);
      border-radius: var(--item-radius);
      border: 1px solid var(--border-color);
      font-size: 14px;
    }
    .kv-key {
      color: var(--text-secondary);
      font-weight: 500;
    }
    .kv-value {
      color: var(--text-main);
      font-weight: 600;
    }
    .item-empty {
      color: var(--text-muted);
      font-style: italic;
      text-align: center;
      padding: 14px;
      font-size: 14px;
    }
    .empty-state {
      background: var(--card-bg);
      border-radius: var(--card-radius);
      padding: 48px 24px;
      text-align: center;
      color: var(--text-muted);
      border: 1px dashed var(--border-color);
      font-size: 16px;
    }
    .memory-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 14px;
      text-align: left;
    }
    .memory-table th {
      padding: 10px 12px;
      color: var(--text-secondary);
      border-bottom: 1px solid var(--border-color);
      font-weight: 600;
      white-space: nowrap;
    }
    .memory-table td {
      padding: 12px;
      border-bottom: 1px solid var(--border-color);
      color: var(--text-main);
      vertical-align: middle;
    }
    .memory-table tr:hover {
      background: rgba(255, 255, 255, 0.02);
    }
    .mem-td-id {
      font-family: monospace;
      color: var(--text-muted);
    }
    .mem-badge-cat {
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 4px;
      background: rgba(139, 92, 246, 0.15);
      color: #c4b5fd;
      border: 1px solid rgba(139, 92, 246, 0.3);
      font-weight: 500;
    }
    .mem-td-key {
      color: var(--text-secondary);
      font-weight: 500;
    }
    .mem-td-content {
      word-break: break-word;
    }
    .mem-td-time {
      font-size: 12px;
      color: var(--text-muted);
      white-space: nowrap;
    }
    .btn-delete-mem {
      padding: 4px 10px;
      background: rgba(239, 68, 68, 0.15);
      color: #f87171;
      border: 1px solid rgba(239, 68, 68, 0.3);
      border-radius: 6px;
      font-size: 12px;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .btn-delete-mem:hover {
      background: rgba(239, 68, 68, 0.3);
      border-color: #ef4444;
    }
  </style>
</head>
<body>
  <div class="container">
    <header class="kiosk-nav">
      <div class="nav-title-group">
        <span class="nav-dot"></span>
        <span class="nav-title">Voice Kiosk 看板</span>
      </div>
      <div class="nav-badges">
        <span class="badge badge-ws" id="ws-badge">WS: 连接中</span>
      </div>
    </header>

    <div class="controls-bar">
      <span id="update-status">就绪</span>
      <div>
        <button type="button" onclick="toggleTokenModal()">设置 Token</button>
        <span style="margin: 0 6px; opacity: 0.4;">|</span>
        <button type="button" onclick="manualRefresh()">刷新</button>
      </div>
    </div>

    <div class="token-modal" id="token-modal">
      <label for="input-token" style="font-size: 13px; font-weight: 600; color: var(--text-main);">访问 Token (DEVICE_TOKEN 或 PUSH_TOKEN):</label>
      <input type="password" id="input-token" placeholder="输入 token 后点击保存">
      <div style="display: flex; justify-content: flex-end; gap: 8px;">
        <button type="button" onclick="saveToken()">保存并刷新</button>
      </div>
    </div>

    <main class="boards-wrapper" id="boards-container">
""" + sections_block + """
    </main>

    <div class="boards-wrapper" id="memories-section-wrapper" style="margin-top: 20px;">
""" + memory_section_html + """
    </div>
  </div>

  <script>
    var PALETTE = ['#06B6D4', '#8B5CF6', '#10B981', '#F59E0B', '#EC4899', '#3B82F6'];

    function getColorForLabel(label, index) {
      if (label && label.length > 0) {
        var hash = 0;
        for (var i = 0; i < label.length; i++) {
          hash = ((hash << 5) - hash) + label.charCodeAt(i);
          hash |= 0;
        }
        return PALETTE[Math.abs(hash) % PALETTE.length];
      }
      return PALETTE[index % PALETTE.length];
    }

    function getStoredToken() {
      var urlParams = new URLSearchParams(window.location.search);
      var qToken = urlParams.get('token');
      if (qToken) {
        try { localStorage.setItem('vk_token', qToken); } catch (e) {}
        return qToken;
      }
      if (window.VoiceKioskHost && typeof window.VoiceKioskHost.deviceToken === 'function') {
        var hToken = window.VoiceKioskHost.deviceToken();
        if (hToken) return hToken;
      }
      try {
        return localStorage.getItem('vk_token') || '';
      } catch (e) {
        return '';
      }
    }

    function toggleTokenModal() {
      var modal = document.getElementById('token-modal');
      var isVisible = modal.style.display === 'block';
      modal.style.display = isVisible ? 'none' : 'block';
      if (!isVisible) {
        var input = document.getElementById('input-token');
        input.value = getStoredToken();
        input.focus();
      }
    }

    function saveToken() {
      var input = document.getElementById('input-token');
      var token = input.value.trim();
      try {
        localStorage.setItem('vk_token', token);
      } catch (e) {}
      document.getElementById('token-modal').style.display = 'none';
      manualRefresh();
    }

    function escapeHtml(str) {
      if (str === null || str === undefined) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    }

    function toggleItem(boardId, itemId) {
      var token = getStoredToken();
      var headers = {};
      if (token) {
        headers['Authorization'] = 'Bearer ' + token;
      }
      fetch('/api/v1/boards/' + encodeURIComponent(boardId) + '/items/' + encodeURIComponent(itemId) + '/toggle', {
        method: 'POST',
        headers: headers
      }).then(function(res) {
        if (res.ok) {
          manualRefresh();
        }
      }).catch(function(err) {});
    }

    function parseBoardToComponentsJs(boardId, data) {
      var components = [];
      var bId = String(boardId || '');
      var title = bId;
      var tag = '';
      var subtitle = '';
      var explicitBadge = '';

      if (data && typeof data === 'object' && !Array.isArray(data)) {
        title = String(data.title || data.name || bId);
        tag = String(data.tag || data.category || data.group || '');
        subtitle = String(data.subtitle || data.description || data.desc || '');
        explicitBadge = String(data.badge || '');
        if (Array.isArray(data.components)) {
          return data.components;
        }
      }

      var rawItems = null;
      var listKey = '';
      if (data && typeof data === 'object' && !Array.isArray(data)) {
        var keys = ['tasks', 'items', 'checklist', 'todo', 'content', 'list'];
        for (var i = 0; i < keys.length; i++) {
          var val = data[keys[i]];
          if (Array.isArray(val)) {
            rawItems = val;
            listKey = keys[i];
            break;
          }
        }
      } else if (Array.isArray(data)) {
        rawItems = data;
      }

      if (rawItems !== null) {
        var explicitType = (data && typeof data === 'object') ? data.type : null;
        var hasDoneKey = false;
        for (var d = 0; d < rawItems.length; d++) {
          if (rawItems[d] && typeof rawItems[d] === 'object') {
            if ('completed' in rawItems[d] || 'done' in rawItems[d] || 'checked' in rawItems[d]) {
              hasDoneKey = true;
              break;
            }
          }
        }
        var isExplicitList = explicitType === 'list' || (listKey === 'list' && !hasDoneKey);

        if (isExplicitList) {
          var listItems = [];
          for (var li = 0; li < rawItems.length; li++) {
            var lItem = rawItems[li];
            var lTxt = (lItem && typeof lItem === 'object') ? (lItem.text || lItem.title || lItem.content || JSON.stringify(lItem)) : String(lItem);
            listItems.push({ text: lTxt });
          }
          var badgeText = explicitBadge || (listItems.length > 0 ? (listItems.length + ' 项') : '');
          components.push({ type: 'title', title: title, tag: tag, subtitle: subtitle, badge: badgeText });
          components.push({ type: 'list', items: listItems });
        } else {
          var checklistItems = [];
          for (var ci = 0; ci < rawItems.length; ci++) {
            var cItem = rawItems[ci];
            if (cItem && typeof cItem === 'object') {
              var cId = String(cItem.id || ('item_' + ci));
              var cLabel = String(cItem.label || cItem.subject || cItem.tag || cItem.category || '');
              var cText = String(cItem.text || cItem.title || cItem.content || cItem.name || '');
              var cDone = Boolean(cItem.completed || cItem.done || cItem.checked);
              var cColor = String(cItem.color || cItem.color_hint || getColorForLabel(cLabel, ci));
              checklistItems.push({ id: cId, label: cLabel, text: cText, completed: cDone, color_hint: cColor });
            } else {
              checklistItems.push({
                id: 'item_' + ci,
                label: '',
                text: String(cItem),
                completed: false,
                color_hint: getColorForLabel('', ci)
              });
            }
          }
          var doneCount = 0;
          for (var dc = 0; dc < checklistItems.length; dc++) {
            if (checklistItems[dc].completed) doneCount++;
          }
          var chkBadge = explicitBadge || (checklistItems.length > 0 ? (doneCount + ' / ' + checklistItems.length + ' 完成') : '');
          components.push({ type: 'title', title: title, tag: tag, subtitle: subtitle, badge: chkBadge });
          components.push({ type: 'checklist', items: checklistItems });
        }
      } else if (data && typeof data === 'object' && !Array.isArray(data)) {
        var pairs = [];
        var ignored = ['title', 'name', 'tag', 'category', 'group', 'subtitle', 'description', 'desc', 'badge', 'date', 'source', 'updated_at', 'timestamp', 'version', 'type'];
        for (var k in data) {
          if (ignored.indexOf(k) === -1) {
            pairs.push({ key: String(k), value: String(data[k]) });
          }
        }
        var kvBadge = explicitBadge || (pairs.length > 0 ? (pairs.length + ' 项') : '');
        components.push({ type: 'title', title: title, tag: tag, subtitle: subtitle, badge: kvBadge });
        if (pairs.length > 0) {
          components.push({ type: 'key_value', pairs: pairs });
        }
      } else {
        components.push({ type: 'title', title: title, tag: tag, subtitle: subtitle, badge: explicitBadge });
        if (data) {
          components.push({ type: 'list', items: [{ text: String(data) }] });
        }
      }
      return components;
    }

    function renderBoardSection(board) {
      var bId = escapeHtml(board.board_id || '');
      var data = board.data || {};
      var dateStr = escapeHtml(data.date || (board.updated_at ? board.updated_at.slice(0, 10) : ''));
      var sourceStr = escapeHtml(board.source || 'muse');

      var components = parseBoardToComponentsJs(bId, data);
      var titleComp = {};
      var bodyHtml = '';

      for (var i = 0; i < components.length; i++) {
        var comp = components[i];
        if (comp.type === 'title') {
          titleComp = comp;
        } else if (comp.type === 'checklist') {
          var items = comp.items || [];
          if (items.length > 0) {
            bodyHtml += '<div class="task-grid">';
            for (var j = 0; j < items.length; j++) {
              var it = items[j];
              var itId = escapeHtml(it.id || '');
              var itLabel = escapeHtml(it.label || '');
              var itText = escapeHtml(it.text || '');
              var itDone = Boolean(it.completed);
              var itColor = escapeHtml(it.color_hint || '#8B5CF6');

              var compClass = itDone ? ' completed' : '';
              var chkClass = itDone ? ' checked' : '';
              var chkIcon = itDone ? '✓' : '';
              var lblHtml = itLabel ? ('<span class="task-label">' + itLabel + '</span>') : '';

              bodyHtml += '<div class="task-card' + compClass + '" data-board-id="' + bId + '" data-item-id="' + itId + '" onclick="toggleItem(\'' + bId + '\', \'' + itId + '\')">'
                + '<div class="task-left">'
                + '<span class="task-dot" style="background-color: ' + itColor + ';"></span>'
                + lblHtml
                + '<span class="task-text">' + itText + '</span>'
                + '</div>'
                + '<div class="task-checkbox' + chkClass + '">' + chkIcon + '</div>'
                + '</div>';
            }
            bodyHtml += '</div>';
          } else {
            bodyHtml += '<p class="item-empty">（暂无清单项）</p>';
          }
        } else if (comp.type === 'list') {
          var lItems = comp.items || [];
          if (lItems.length > 0) {
            bodyHtml += '<ul class="generic-list">';
            for (var k = 0; k < lItems.length; k++) {
              bodyHtml += '<li class="generic-list-item">'
                + '<span class="list-bullet"></span>'
                + '<span class="list-text">' + escapeHtml(lItems[k].text || '') + '</span>'
                + '</li>';
            }
            bodyHtml += '</ul>';
          } else {
            bodyHtml += '<p class="item-empty">（暂无列表内容）</p>';
          }
        } else if (comp.type === 'key_value') {
          var pairs = comp.pairs || [];
          if (pairs.length > 0) {
            bodyHtml += '<div class="kv-grid">';
            for (var m = 0; m < pairs.length; m++) {
              bodyHtml += '<div class="kv-row">'
                + '<span class="kv-key">' + escapeHtml(pairs[m].key || '') + '</span>'
                + '<span class="kv-value">' + escapeHtml(pairs[m].value || '') + '</span>'
                + '</div>';
            }
            bodyHtml += '</div>';
          }
        }
      }

      var compTitle = escapeHtml(titleComp.title || bId);
      var compTag = escapeHtml(titleComp.tag || '');
      var compSubtitle = escapeHtml(titleComp.subtitle || '');
      var compBadge = escapeHtml(titleComp.badge || '');

      var tagHtml = compTag ? ('<span class="board-tag">' + compTag + '</span>') : '';
      var subHtml = compSubtitle ? ('<p class="board-subtitle">' + compSubtitle + '</p>') : '';
      var badgeHtml = compBadge ? ('<span class="board-badge">' + compBadge + '</span>') : '';
      var dateHtml = dateStr ? ('<span class="meta-date">' + dateStr + '</span>') : '';

      return '<section class="board-section" id="board-' + bId + '" data-board-id="' + bId + '">'
        + '<div class="board-header">'
        + '<div class="header-left">'
        + '<div class="title-row">'
        + '<span class="indicator-dot"></span>'
        + '<h2 class="board-title">' + compTitle + '</h2>'
        + tagHtml
        + '</div>'
        + subHtml
        + '<div class="board-meta">'
        + '<span class="meta-id">' + bId + '</span>'
        + dateHtml
        + '<span class="meta-source">来源: ' + sourceStr + '</span>'
        + '</div>'
        + '</div>'
        + '<div class="header-right">'
        + badgeHtml
        + '</div>'
        + '</div>'
        + '<div class="board-body">'
        + bodyHtml
        + '</div>'
        + '</section>';
    }

    function fetchBoardsWithToken(token) {
      var statusEl = document.getElementById('update-status');
      var container = document.getElementById('boards-container');
      statusEl.textContent = '更新中...';

      var headers = {};
      if (token) {
        headers['Authorization'] = 'Bearer ' + token;
      }

      fetch('/api/v1/boards', { headers: headers })
        .then(function(res) {
          if (!res.ok) {
            if (res.status === 401 || res.status === 403) {
              statusEl.textContent = '需要有效 Token';
            } else {
              statusEl.textContent = '获取列表失败: ' + res.status;
            }
            throw new Error('fetch boards failed: ' + res.status);
          }
          return res.json();
        })
        .then(function(data) {
          var boardIds = Array.isArray(data) ? data : (data.boards || []);
          if (boardIds.length === 0) {
            container.innerHTML = '<div class="empty-state" id="empty-state">暂无看板数据</div>';
            statusEl.textContent = '已更新 (0 个看板)';
            return;
          }
          var promises = boardIds.map(function(id) {
            return fetch('/api/v1/boards/' + encodeURIComponent(id), { headers: headers })
              .then(function(r) { return r.ok ? r.json() : null; })
              .catch(function() { return null; });
          });
          return Promise.all(promises);
        })
        .then(function(boards) {
          if (!boards) return;
          var validBoards = boards.filter(function(b) { return b !== null; });
          if (validBoards.length === 0) {
            container.innerHTML = '<div class="empty-state" id="empty-state">暂无看板数据</div>';
          } else {
            var cardsHtml = '';
            for (var i = 0; i < validBoards.length; i++) {
              cardsHtml += renderBoardSection(validBoards[i]);
            }
            container.innerHTML = cardsHtml;
          }
          var now = new Date();
          var timeStr = (now.getHours() < 10 ? '0' : '') + now.getHours() + ':' +
                        (now.getMinutes() < 10 ? '0' : '') + now.getMinutes() + ':' +
                        (now.getSeconds() < 10 ? '0' : '') + now.getSeconds();
          statusEl.textContent = '更新于 ' + timeStr;
        })
        .catch(function(err) {});
    }

    function fetchMemoriesWithToken(token) {
      var tbody = document.getElementById('memory-table-body');
      var badge = document.getElementById('memory-count-badge');
      if (!tbody) return;

      var headers = {};
      if (token) {
        headers['Authorization'] = 'Bearer ' + token;
      }

      fetch('/api/v1/memories', { headers: headers })
        .then(function(res) {
          if (!res.ok) {
            throw new Error('fetch memories failed: ' + res.status);
          }
          return res.json();
        })
        .then(function(items) {
          if (!Array.isArray(items)) return;
          if (badge) {
            badge.textContent = items.length + ' 条记忆';
          }
          if (items.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="item-empty">暂无长期记忆</td></tr>';
            return;
          }
          var rowsHtml = '';
          for (var i = 0; i < items.length; i++) {
            var it = items[i];
            var id = escapeHtml(it.id);
            var cat = escapeHtml(it.category || '');
            var key = escapeHtml(it.key || '-');
            var content = escapeHtml(it.content || '');
            var timeStr = '';
            if (it.updated_at) {
              var d = new Date(it.updated_at * 1000);
              timeStr = d.getFullYear() + '-' +
                        ('0' + (d.getMonth() + 1)).slice(-2) + '-' +
                        ('0' + d.getDate()).slice(-2) + ' ' +
                        ('0' + d.getHours()).slice(-2) + ':' +
                        ('0' + d.getMinutes()).slice(-2) + ':' +
                        ('0' + d.getSeconds()).slice(-2);
            }
            rowsHtml += '<tr id="memory-row-' + id + '">'
              + '<td class="mem-td-id">' + id + '</td>'
              + '<td class="mem-td-cat"><span class="mem-badge-cat">' + cat + '</span></td>'
              + '<td class="mem-td-key">' + key + '</td>'
              + '<td class="mem-td-content">' + content + '</td>'
              + '<td class="mem-td-time">' + timeStr + '</td>'
              + '<td class="mem-td-action"><button type="button" class="btn-delete-mem" onclick="deleteMemory(' + id + ')">删除</button></td>'
              + '</tr>';
          }
          tbody.innerHTML = rowsHtml;
        })
        .catch(function(err) {});
    }

    function deleteMemory(id) {
      if (!confirm('确定删除此记忆？')) return;
      var token = getStoredToken();
      var headers = {};
      if (token) {
        headers['Authorization'] = 'Bearer ' + token;
      }
      fetch('/api/v1/memories?id=' + encodeURIComponent(id), {
        method: 'DELETE',
        headers: headers
      }).then(function(res) {
        if (res.ok) {
          manualRefresh();
        } else {
          alert('删除失败: ' + res.status);
        }
      }).catch(function(err) {
        alert('删除出错: ' + err);
      });
    }

    function manualRefresh() {
      var token = getStoredToken();
      fetchBoardsWithToken(token);
      fetchMemoriesWithToken(token);
    }

    var ws = null;
    function connectWs() {
      var badge = document.getElementById('ws-badge');
      var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      var wsUrl = protocol + '//' + window.location.host + '/ws';

      try {
        ws = new WebSocket(wsUrl);
      } catch (e) {
        badge.className = 'badge badge-ws error';
        badge.textContent = 'WS: 失败';
        return;
      }

      ws.onopen = function() {
        badge.className = 'badge badge-ws connected';
        badge.textContent = 'WS: 已连接';
      };

      ws.onmessage = function(event) {
        try {
          var msg = JSON.parse(event.data);
          if (msg.type === 'board_update' || msg.board_id) {
            manualRefresh();
          }
        } catch (e) {}
      };

      ws.onclose = function() {
        badge.className = 'badge badge-ws';
        badge.textContent = 'WS: 重连中';
        setTimeout(connectWs, 3000);
      };

      ws.onerror = function() {
        badge.className = 'badge badge-ws error';
        badge.textContent = 'WS: 异常';
      };
    }

    window.addEventListener('DOMContentLoaded', function() {
      connectWs();
      var token = getStoredToken();
      if (token) {
        manualRefresh();
      }
      setInterval(function() {
        var t = getStoredToken();
        if (t) {
          fetchBoardsWithToken(t);
          fetchMemoriesWithToken(t);
        }
      }, 30000);
    });
  </script>
</body>
</html>
"""
    return html_doc
