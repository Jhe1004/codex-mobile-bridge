package org.codexmobile.bridge

import android.content.Context

internal class ConversationDraftStore private constructor(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(threadId: String): String =
        if (threadId.isBlank()) "" else preferences.getString(threadId, "").orEmpty()

    fun save(threadId: String, text: String) {
        if (threadId.isBlank()) return
        preferences.edit().apply {
            if (text.isEmpty()) remove(threadId) else putString(threadId, text)
        }.apply()
    }

    fun clear(threadId: String) {
        if (threadId.isNotBlank()) preferences.edit().remove(threadId).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "conversation_drafts_v1"

        fun forContext(context: Context): ConversationDraftStore =
            ConversationDraftStore(context.applicationContext)
    }
}
