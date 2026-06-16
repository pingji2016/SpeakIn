package com.speakin.app.ui.navigation

object Routes {
    const val NOTE_LIST = "note_list"
    const val NOTE_DETAIL = "note_detail/{noteId}"
    const val SETTINGS = "settings"

    fun noteDetail(noteId: String) = "note_detail/$noteId"
}
