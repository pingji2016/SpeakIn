package com.speakin.app.ui.navigation

object Routes {
    const val NOTE_LIST = "note_list"
    const val NOTE_DETAIL = "note_detail/{noteId}"
    const val AUDIO_EDITOR = "audio_editor/{noteId}/{segmentIndex}"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun noteDetail(noteId: String) = "note_detail/$noteId"

    fun audioEditor(noteId: String, segmentIndex: Int) = "audio_editor/$noteId/$segmentIndex"
}
