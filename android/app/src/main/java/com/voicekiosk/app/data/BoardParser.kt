package com.voicekiosk.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses raw board JSON from GET /api/v1/boards/{id} into BoardModel and generic components.
 * Matches relay-bridge/app/dashboard.py parse_board_to_components logic 1:1.
 * Contains ZERO hardcoded domain names, subject names, or person names.
 * Contains ZERO estimated-time fields.
 */
object BoardParser {

    private val IGNORED_KEY_VALUE_KEYS = setOf(
        "title", "name", "tag", "category", "group", "subtitle",
        "description", "desc", "badge", "date", "source", "updated_at",
        "timestamp", "version", "type"
    )

    fun parseBoard(boardId: String, rawJsonStr: String): BoardModel {
        val root = JSONObject(rawJsonStr)
        val bId = root.optString("board_id", boardId).ifEmpty { boardId }
        val updatedAt = root.optString("updated_at", root.optString("timestamp", ""))
        val source = root.optString("source", "muse")
        val dataObj = root.opt("data") ?: root

        val (titleComp, components) = parseDataToComponents(bId, dataObj)

        return BoardModel(
            boardId = bId,
            titleComponent = titleComp,
            components = components,
            updatedAt = updatedAt,
            source = source,
            rawJson = rawJsonStr
        )
    }

    private fun parseDataToComponents(boardId: String, data: Any): Pair<TitleComponent, List<BoardComponent>> {
        var title = ""
        var tag = ""
        var subtitle = ""
        var explicitBadge = ""

        if (data is JSONObject) {
            title = data.optString("title").ifEmpty { data.optString("name").ifEmpty { boardId } }
            tag = data.optString("tag").ifEmpty { data.optString("category").ifEmpty { data.optString("group", "") } }
            subtitle = data.optString("subtitle").ifEmpty { data.optString("description").ifEmpty { data.optString("desc", "") } }
            explicitBadge = data.optString("badge", "")

            // If data explicitly defines structured components, parse them directly
            val compArray = data.optJSONArray("components")
            if (compArray != null) {
                return parseExplicitComponents(boardId, title, tag, subtitle, explicitBadge, compArray)
            }
        } else {
            title = boardId
        }

        // Check for items/tasks list
        var rawItems: JSONArray? = null
        var listKey = ""
        if (data is JSONObject) {
            val candidateKeys = listOf("tasks", "items", "checklist", "todo", "content", "list")
            for (key in candidateKeys) {
                val candidate = data.optJSONArray(key)
                if (candidate != null) {
                    rawItems = candidate
                    listKey = key
                    break
                }
            }
        } else if (data is JSONArray) {
            rawItems = data
        }

        if (rawItems != null) {
            val explicitType = if (data is JSONObject) data.optString("type", "") else ""
            var hasDoneFlag = false
            for (i in 0 until rawItems.length()) {
                val it = rawItems.opt(i)
                if (it is JSONObject && (it.has("completed") || it.has("done") || it.has("checked"))) {
                    hasDoneFlag = true
                    break
                }
            }
            val isExplicitList = explicitType == "list" || (listKey == "list" && !hasDoneFlag)

            if (isExplicitList) {
                val listItems = mutableListOf<ListItem>()
                for (i in 0 until rawItems.length()) {
                    val it = rawItems.opt(i)
                    val text = if (it is JSONObject) {
                        it.optString("text").ifEmpty {
                            it.optString("title").ifEmpty {
                                it.optString("content", it.toString())
                            }
                        }
                    } else {
                        it?.toString().orEmpty()
                    }
                    listItems.add(ListItem(text = text))
                }
                val badge = explicitBadge.ifEmpty { if (listItems.isNotEmpty()) "${listItems.size} 项" else "" }
                val titleComp = TitleComponent(title = title, tag = tag, subtitle = subtitle, badge = badge)
                return Pair(titleComp, listOf(BoardComponent.ListComp(listItems)))
            } else {
                val checklistItems = mutableListOf<ChecklistItem>()
                for (i in 0 until rawItems.length()) {
                    val it = rawItems.opt(i)
                    val item = if (it is JSONObject) {
                        val itemId = it.optString("id").ifEmpty { "item_$i" }
                        val label = it.optString("label").ifEmpty {
                            it.optString("subject").ifEmpty {
                                it.optString("tag").ifEmpty {
                                    it.optString("category", "")
                                }
                            }
                        }
                        val text = it.optString("text").ifEmpty {
                            it.optString("title").ifEmpty {
                                it.optString("content").ifEmpty {
                                    it.optString("name", "")
                                }
                            }
                        }
                        val isDone = it.optBoolean("completed", false) ||
                                it.optBoolean("done", false) ||
                                it.optBoolean("checked", false)
                        val color = it.optString("color").ifEmpty {
                            it.optString("color_hint").ifEmpty {
                                BoardColorPalette.getColorForLabel(label, i)
                            }
                        }
                        ChecklistItem(
                            id = itemId,
                            label = label,
                            text = text,
                            completed = isDone,
                            colorHint = color
                        )
                    } else {
                        val itemId = "item_$i"
                        val text = it?.toString().orEmpty()
                        val color = BoardColorPalette.getColorForLabel("", i)
                        ChecklistItem(
                            id = itemId,
                            label = "",
                            text = text,
                            completed = false,
                            colorHint = color
                        )
                    }
                    checklistItems.add(item)
                }

                val completedCount = checklistItems.count { it.completed }
                val totalCount = checklistItems.size
                val badge = explicitBadge.ifEmpty {
                    if (totalCount > 0) "$completedCount / $totalCount 完成" else ""
                }
                val titleComp = TitleComponent(title = title, tag = tag, subtitle = subtitle, badge = badge)
                return Pair(titleComp, listOf(BoardComponent.Checklist(checklistItems)))
            }
        } else if (data is JSONObject) {
            val pairs = mutableListOf<KeyValuePair>()
            val keys = data.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k !in IGNORED_KEY_VALUE_KEYS) {
                    val v = data.opt(k)?.toString().orEmpty()
                    pairs.add(KeyValuePair(key = k, value = v))
                }
            }
            val badge = explicitBadge.ifEmpty { if (pairs.isNotEmpty()) "${pairs.size} 项" else "" }
            val titleComp = TitleComponent(title = title, tag = tag, subtitle = subtitle, badge = badge)
            val comps = if (pairs.isNotEmpty()) listOf(BoardComponent.KeyValue(pairs)) else emptyList()
            return Pair(titleComp, comps)
        } else {
            val titleComp = TitleComponent(title = title, tag = tag, subtitle = subtitle, badge = explicitBadge)
            val comps = if (data.toString().isNotEmpty()) {
                listOf(BoardComponent.ListComp(listOf(ListItem(text = data.toString()))))
            } else {
                emptyList()
            }
            return Pair(titleComp, comps)
        }
    }

    private fun parseExplicitComponents(
        boardId: String,
        defaultTitle: String,
        defaultTag: String,
        defaultSubtitle: String,
        defaultBadge: String,
        compArray: JSONArray
    ): Pair<TitleComponent, List<BoardComponent>> {
        var titleComp = TitleComponent(defaultTitle, defaultTag, defaultSubtitle, defaultBadge)
        val comps = mutableListOf<BoardComponent>()

        for (i in 0 until compArray.length()) {
            val obj = compArray.optJSONObject(i) ?: continue
            when (obj.optString("type")) {
                "title" -> {
                    titleComp = TitleComponent(
                        title = obj.optString("title", defaultTitle).ifEmpty { defaultTitle },
                        tag = obj.optString("tag", defaultTag),
                        subtitle = obj.optString("subtitle", defaultSubtitle),
                        badge = obj.optString("badge", defaultBadge)
                    )
                }
                "checklist" -> {
                    val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                    val items = mutableListOf<ChecklistItem>()
                    for (j in 0 until itemsArr.length()) {
                        val it = itemsArr.optJSONObject(j) ?: continue
                        items.add(
                            ChecklistItem(
                                id = it.optString("id", "item_$j"),
                                label = it.optString("label", ""),
                                text = it.optString("text", ""),
                                completed = it.optBoolean("completed", false),
                                colorHint = it.optString("color_hint", "")
                            )
                        )
                    }
                    comps.add(BoardComponent.Checklist(items))
                }
                "list" -> {
                    val itemsArr = obj.optJSONArray("items") ?: JSONArray()
                    val items = mutableListOf<ListItem>()
                    for (j in 0 until itemsArr.length()) {
                        val it = itemsArr.opt(j)
                        val text = if (it is JSONObject) it.optString("text", "") else it.toString()
                        items.add(ListItem(text = text))
                    }
                    comps.add(BoardComponent.ListComp(items))
                }
                "key_value" -> {
                    val pairsArr = obj.optJSONArray("pairs") ?: JSONArray()
                    val pairs = mutableListOf<KeyValuePair>()
                    for (j in 0 until pairsArr.length()) {
                        val p = pairsArr.optJSONObject(j) ?: continue
                        pairs.add(KeyValuePair(key = p.optString("key", ""), value = p.optString("value", "")))
                    }
                    comps.add(BoardComponent.KeyValue(pairs))
                }
            }
        }
        return Pair(titleComp, comps)
    }
}
