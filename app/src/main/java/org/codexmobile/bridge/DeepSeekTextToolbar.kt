package org.codexmobile.bridge

import android.content.ClipboardManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.math.ceil
import kotlin.math.floor

internal class DeepSeekTextToolbar(
    private val view: View,
    var onAskSelectedText: (String) -> Unit,
) : TextToolbar {
    private var actionMode: ActionMode? = null
    private var selectionRect: ComposeRect = ComposeRect.Zero
    private var onCopyRequested: (() -> Unit)? = null
    private var onPasteRequested: (() -> Unit)? = null
    private var onCutRequested: (() -> Unit)? = null
    private var onSelectAllRequested: (() -> Unit)? = null
    private var onAutofillRequested: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: ComposeRect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) {
        selectionRect = rect
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested
        this.onAutofillRequested = onAutofillRequested
        if (actionMode == null) {
            status = TextToolbarStatus.Shown
            actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
            if (actionMode == null) status = TextToolbarStatus.Hidden
        } else {
            actionMode?.invalidate()
            actionMode?.invalidateContentRect()
        }
    }

    override fun showMenu(
        rect: ComposeRect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        showMenu(
            rect,
            onCopyRequested,
            onPasteRequested,
            onCutRequested,
            onSelectAllRequested,
            null,
        )
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            populateMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            populateMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                COPY_ID -> onCopyRequested?.invoke()
                PASTE_ID -> onPasteRequested?.invoke()
                CUT_ID -> onCutRequested?.invoke()
                SELECT_ALL_ID -> onSelectAllRequested?.invoke()
                AUTOFILL_ID -> onAutofillRequested?.invoke()
                ASK_DEEPSEEK_ID -> copySelectionAndAskDeepSeek()
                else -> return false
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            status = TextToolbarStatus.Hidden
        }

        override fun onGetContentRect(mode: ActionMode, target: View, outRect: Rect) {
            outRect.set(
                floor(selectionRect.left).toInt(),
                floor(selectionRect.top).toInt(),
                ceil(selectionRect.right).toInt(),
                ceil(selectionRect.bottom).toInt(),
            )
        }
    }

    private fun populateMenu(menu: Menu) {
        if (onCopyRequested != null) {
            // HyperOS and Android may inject Translate/Read Aloud actions. Put the requested
            // action first and force it onto the floating toolbar so it cannot be displaced.
            menu.add(0, ASK_DEEPSEEK_ID, 0, "问 DeepSeek")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (onCopyRequested != null) add(menu, COPY_ID, 1, android.R.string.copy)
        if (onPasteRequested != null) add(menu, PASTE_ID, 2, android.R.string.paste)
        if (onCutRequested != null) add(menu, CUT_ID, 3, android.R.string.cut)
        if (onSelectAllRequested != null) add(menu, SELECT_ALL_ID, 4, android.R.string.selectAll)
        if (onAutofillRequested != null) add(menu, AUTOFILL_ID, 5, android.R.string.autofill)
    }

    private fun add(menu: Menu, id: Int, order: Int, titleResource: Int) {
        menu.add(0, id, order, titleResource).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    private fun copySelectionAndAskDeepSeek() {
        val copy = onCopyRequested ?: return
        captureCopiedSelection(view, copy, onAskSelectedText)
    }

    companion object {
        private const val COPY_ID = 10_101
        private const val PASTE_ID = 10_102
        private const val CUT_ID = 10_103
        private const val SELECT_ALL_ID = 10_104
        private const val AUTOFILL_ID = 10_105
        private const val ASK_DEEPSEEK_ID = 10_106
    }
}

internal val DeepSeekContextMenuKey = Any()

internal fun askDeepSeekFromFocusedSelection(view: View, onSelectedText: (String) -> Unit) {
    captureCopiedSelection(
        view = view,
        triggerCopy = {
            val now = SystemClock.uptimeMillis()
            view.dispatchKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON),
            )
            view.dispatchKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON),
            )
        },
        onSelectedText = onSelectedText,
    )
}

private fun captureCopiedSelection(
    view: View,
    triggerCopy: () -> Unit,
    onSelectedText: (String) -> Unit,
) {
        val clipboard = view.context.getSystemService(ClipboardManager::class.java) ?: return
        var delivered = false
        lateinit var listener: ClipboardManager.OnPrimaryClipChangedListener

        fun deliverSelection() {
            if (delivered) return
            val selected = clipboard.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(view.context)
                ?.toString()
                ?.trim()
                .orEmpty()
            if (selected.isBlank()) return
            delivered = true
            clipboard.removePrimaryClipChangedListener(listener)
            onSelectedText(selected)
        }

        listener = ClipboardManager.OnPrimaryClipChangedListener { view.post { deliverSelection() } }
        clipboard.addPrimaryClipChangedListener(listener)
        triggerCopy()
        view.postDelayed({
            deliverSelection()
            clipboard.removePrimaryClipChangedListener(listener)
        }, 180L)
}
