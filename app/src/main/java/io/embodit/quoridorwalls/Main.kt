package io.embodit.quoridorwalls

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.quoridorwalls.ui.Containers
import io.embodit.quoridorwalls.ui.board.BoardScreen
import io.embodit.quoridorwalls.ui.control.ControlWindow
import io.embodit.quoridorwalls.ui.theme.QTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the board volume (default container,
 * 0.66 x 0.44 x 0.62 m from the manifest) and the control window (Planar 520 x 400 dp, min 480 x 320, max 900 x 640) to
 * its right. First open and every reopen use Placement.right(board) (profile D44: no "in front of you" in Shared Space).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            QTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 520.dp, height = 400.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            QTheme {
                Box(Modifier.windowConstraints(minWidth = 480.dp, minHeight = 320.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
