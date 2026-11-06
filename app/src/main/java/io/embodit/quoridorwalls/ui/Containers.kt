package io.embodit.quoridorwalls.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "QuoridorBoard"
    const val CONTROL = "QuoridorControl"
    const val CONTROL_TAG = "control"
}

/** The four pages of the native TabBar (design panels.control.pages) and the state id each one is captured as. */
enum class Page(val stateId: String) { PLAY("S03"), NEW("S02"), RULES("S12"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
    var newConfirm by mutableStateOf(false)
}
