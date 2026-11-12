import html
import json
from typing import Any


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
        for key in ["items", "tasks", "homework", "list", "content"]:
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
        # If no standard list key, list string values or key-value pairs
        pairs: list[str] = []
        for k, v in data.items():
            if k in ["title", "date", "source", "version"]:
                continue
            pairs.append(f"{k}: {v}")
        if pairs:
            return pairs
    if isinstance(data, str):
        return [data]
    return []


def render_dashboard(boards: list[dict[str, Any]]) -> str:
    rendered_cards: list[str] = []

    if not boards:
        rendered_cards.append(
            '<div class="empty-state" id="empty-state">暂无看板数据</div>'
        )
    else:
        for b in boards:
            b_id = html.escape(str(b.get("board_id", "")))
            data = b.get("data", {})
            title = html.escape(_extract_board_title(b_id, data))
            date_str = html.escape(_extract_board_date(b))
            source_str = html.escape(str(b.get("source", "muse")))
            items = _extract_board_items(data)

            items_html_parts: list[str] = []
            if items:
                items_html_parts.append('<ul class="item-list">')
                for item in items:
                    escaped_item = html.escape(item)
                    items_html_parts.append(
                        '<li class="item">'
                        + '<span class="item-bullet"></span>'
                        + '<span class="item-text">'
                        + escaped_item
                        + '</span>'
                        + '</li>'
                    )
                items_html_parts.append('</ul>')
            else:
                items_html_parts.append('<p class="item-empty">（暂无明细内容）</p>')

            items_html = "".join(items_html_parts)

            card_html = (
                '<div class="board-card" id="card-' + b_id + '" data-board-id="' + b_id + '">'
                + '<div class="card-header">'
                + '<div class="card-title-group">'
                + '<h2 class="card-title">' + title + '</h2>'
                + '<span class="card-id-badge">' + b_id + '</span>'
                + '</div>'
                + '<div class="card-meta">'
                + (('<span class="meta-date">' + date_str + '</span>') if date_str else "")
                + '<span class="meta-source">来源: ' + source_str + '</span>'
                + '</div>'
                + '</div>'
                + '<div class="card-body">'
                + items_html
                + '</div>'
                + '</div>'
            )
            rendered_cards.append(card_html)

    cards_block = "\n".join(rendered_cards)

    # HTML page construction (strictly without backticks)
    html_doc = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>Voice Kiosk 看板</title>
  <style>
    :root {
      --bg-color: #f8fafc;
      --card-bg: #ffffff;
      --text-main: #0f172a;
      --text-muted: #64748b;
      --primary: #2563eb;
      --primary-light: #eff6ff;
      --success: #16a34a;
      --border-color: #e2e8f0;
      --card-radius: 16px;
      --card-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -2px rgba(0, 0, 0, 0.05);
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
      padding: 16px;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
    }
    .container {
      width: 100%;
      max-width: 640px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 16px;
      background: var(--card-bg);
      border-radius: var(--card-radius);
      box-shadow: var(--card-shadow);
      border: 1px solid var(--border-color);
    }
    .header-title {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .header-badges {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .badge {
      font-size: 12px;
      padding: 4px 8px;
      border-radius: 9999px;
      font-weight: 500;
    }
    .badge-ws {
      background: #f1f5f9;
      color: var(--text-muted);
    }
    .badge-ws.connected {
      background: #dcfce7;
      color: var(--success);
    }
    .badge-ws.error {
      background: #fee2e2;
      color: #dc2626;
    }
    .board-card {
      background: var(--card-bg);
      border-radius: var(--card-radius);
      box-shadow: var(--card-shadow);
      border: 1px solid var(--border-color);
      overflow: hidden;
    }
    .card-header {
      padding: 16px 20px;
      border-bottom: 1px solid var(--border-color);
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      background: linear-gradient(to right, #ffffff, var(--primary-light));
    }
    .card-title-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .card-title {
      font-size: 18px;
      font-weight: 700;
      color: var(--text-main);
      word-break: break-word;
    }
    .card-id-badge {
      font-size: 11px;
      color: var(--text-muted);
      background: rgba(0, 0, 0, 0.04);
      padding: 2px 6px;
      border-radius: 4px;
      align-self: flex-start;
      font-family: monospace;
    }
    .card-meta {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 4px;
      font-size: 12px;
      color: var(--text-muted);
      white-space: nowrap;
    }
    .meta-date {
      font-weight: 600;
      color: var(--primary);
    }
    .card-body {
      padding: 20px;
    }
    .item-list {
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .item {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      font-size: 16px;
      color: var(--text-main);
      line-height: 1.6;
      padding: 8px 12px;
      background: #f8fafc;
      border-radius: 8px;
      border-left: 4px solid var(--primary);
    }
    .item-bullet {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--primary);
      margin-top: 8px;
      flex-shrink: 0;
    }
    .item-text {
      flex: 1;
      word-break: break-word;
    }
    .item-empty {
      color: var(--text-muted);
      font-style: italic;
      text-align: center;
      padding: 12px;
    }
    .empty-state {
      background: var(--card-bg);
      border-radius: var(--card-radius);
      padding: 40px 20px;
      text-align: center;
      color: var(--text-muted);
      border: 1px dashed var(--border-color);
      font-size: 16px;
    }
    .controls {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 16px;
      font-size: 12px;
      color: var(--text-muted);
    }
    .controls a, .controls button {
      color: var(--primary);
      text-decoration: none;
      background: none;
      border: none;
      font-size: 12px;
      cursor: pointer;
    }
    .token-modal {
      display: none;
      padding: 12px;
      background: var(--card-bg);
      border-radius: 12px;
      border: 1px solid var(--border-color);
      margin-top: 8px;
    }
    .token-modal input {
      width: 100%;
      padding: 8px;
      font-size: 14px;
      border: 1px solid var(--border-color);
      border-radius: 6px;
      margin-top: 6px;
      margin-bottom: 8px;
    }
    .token-modal button {
      padding: 6px 14px;
      background: var(--primary);
      color: white;
      border: none;
      border-radius: 6px;
      cursor: pointer;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="header-title">
        <span>📋</span>
        <span>Voice Kiosk 看板</span>
      </div>
      <div class="header-badges">
        <span class="badge badge-ws" id="ws-badge">WS: 连接中</span>
      </div>
    </header>

    <div class="controls">
      <span id="update-status">就绪</span>
      <div>
        <button type="button" onclick="toggleTokenModal()">设置 Token</button>
        <span style="margin: 0 4px;">|</span>
        <button type="button" onclick="manualRefresh()">刷新</button>
      </div>
    </div>

    <div class="token-modal" id="token-modal">
      <label for="input-token" style="font-size: 13px; font-weight: 600;">访问 Token (DEVICE_TOKEN 或 PUSH_TOKEN):</label>
      <input type="password" id="input-token" placeholder="输入 token 后点击保存">
      <div style="display: flex; justify-content: flex-end; gap: 8px;">
        <button type="button" onclick="saveToken()">保存并刷新</button>
      </div>
    </div>

    <main id="boards-container">
""" + cards_block + """
    </main>
  </div>

  <script>
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

    function renderBoardCard(board) {
      var bId = escapeHtml(board.board_id || '');
      var data = board.data || {};
      var title = escapeHtml(data.title || data.name || bId);
      var dateStr = escapeHtml(data.date || (board.updated_at ? board.updated_at.slice(0, 10) : ''));
      var sourceStr = escapeHtml(board.source || 'muse');

      var items = [];
      if (Array.isArray(data)) {
        items = data;
      } else if (typeof data === 'object') {
        var itemKeys = ['items', 'tasks', 'homework', 'list', 'content'];
        for (var i = 0; i < itemKeys.length; i++) {
          var val = data[itemKeys[i]];
          if (Array.isArray(val)) {
            for (var j = 0; j < val.length; j++) {
              var it = val[j];
              if (it && typeof it === 'object') {
                items.push(it.text || it.title || it.content || JSON.stringify(it));
              } else {
                items.push(String(it));
              }
            }
            break;
          } else if (typeof val === 'string') {
            items.push(val);
            break;
          }
        }
        if (items.length === 0) {
          for (var k in data) {
            if (['title', 'date', 'source', 'version'].indexOf(k) === -1) {
              items.push(k + ': ' + data[k]);
            }
          }
        }
      } else if (typeof data === 'string') {
        items.push(data);
      }

      var itemsHtml = '';
      if (items.length > 0) {
        itemsHtml += '<ul class="item-list">';
        for (var idx = 0; idx < items.length; idx++) {
          itemsHtml += '<li class="item">'
            + '<span class="item-bullet"></span>'
            + '<span class="item-text">' + escapeHtml(items[idx]) + '</span>'
            + '</li>';
        }
        itemsHtml += '</ul>';
      } else {
        itemsHtml = '<p class="item-empty">（暂无明细内容）</p>';
      }

      var dateTag = dateStr ? '<span class="meta-date">' + dateStr + '</span>' : '';
      return '<div class="board-card" id="card-' + bId + '" data-board-id="' + bId + '">'
        + '<div class="card-header">'
        + '<div class="card-title-group">'
        + '<h2 class="card-title">' + title + '</h2>'
        + '<span class="card-id-badge">' + bId + '</span>'
        + '</div>'
        + '<div class="card-meta">'
        + dateTag
        + '<span class="meta-source">来源: ' + sourceStr + '</span>'
        + '</div>'
        + '</div>'
        + '<div class="card-body">'
        + itemsHtml
        + '</div>'
        + '</div>';
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
              cardsHtml += renderBoardCard(validBoards[i]);
            }
            container.innerHTML = cardsHtml;
          }
          var now = new Date();
          var timeStr = (now.getHours() < 10 ? '0' : '') + now.getHours() + ':' +
                        (now.getMinutes() < 10 ? '0' : '') + now.getMinutes() + ':' +
                        (now.getSeconds() < 10 ? '0' : '') + now.getSeconds();
          statusEl.textContent = '更新于 ' + timeStr;
        })
        .catch(function(err) {
          // Keep current rendered boards on network or auth errors
        });
    }

    function manualRefresh() {
      var token = getStoredToken();
      fetchBoardsWithToken(token);
    }

    // Connect to WebSocket /ws
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
        }
      }, 30000);
    });
  </script>
</body>
</html>
"""
    return html_doc
