package com.voicekiosk.app.data

/**
 * Data models for generic board rendering.
 * Zero hardcoded domain names, subject names, person names, or board IDs.
 * Zero estimated-time fields per user decision.
 */
data class BoardModel(
    val boardId: String,
    val titleComponent: TitleComponent,
    val components: List<BoardComponent>,
    val updatedAt: String = "",
    val source: String = "muse",
    val rawJson: String = ""
)

data class TitleComponent(
    val title: String,
    val tag: String = "",
    val subtitle: String = "",
    val badge: String = ""
)

sealed class BoardComponent {
    data class Checklist(val items: List<ChecklistItem>) : BoardComponent()
    data class ListComp(val items: List<ListItem>) : BoardComponent()
    data class KeyValue(val pairs: List<KeyValuePair>) : BoardComponent()
}

data class ChecklistItem(
    val id: String,
    val label: String = "",
    val text: String,
    var completed: Boolean = false,
    val colorHint: String = ""
)

data class ListItem(
    val text: String
)

data class KeyValuePair(
    val key: String,
    val value: String
)
