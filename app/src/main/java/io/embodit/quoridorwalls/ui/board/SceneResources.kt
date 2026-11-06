package io.embodit.quoridorwalls.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.quoridorwalls.domain.Layout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * Marks of the volume (rings, dots, the path bands, the ghost wall, slot glows) and the invisible collider shapes. The
 * visible objects (board, walls, pawns) are GLBs built by tools/build_models.py; only abstract marks are SDK meshes.
 * Creating a resource waits on the renderer, so everything is created concurrently on Dispatchers.IO.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var ringSelect: MeshResource; private set
    lateinit var ringJump: MeshResource; private set
    lateinit var ringCell: MeshResource; private set
    lateinit var ringGoal: MeshResource; private set
    lateinit var dot: MeshResource; private set
    /** 1 x 1 m plane in XY, scaled per use (bands, slot glows). */
    lateinit var unitPlane: MeshResource; private set
    lateinit var ghost: MeshResource; private set
    lateinit var flash: MeshResource; private set

    lateinit var cream: Material; private set
    lateinit var creamFaint: Material; private set
    lateinit var blue: Material; private set
    lateinit var amber: Material; private set
    lateinit var red: Material; private set
    lateinit var faint: Material; private set
    lateinit var gold: Material; private set
    lateinit var bandGold: Material; private set
    lateinit var bandBlue: Material; private set
    lateinit var ghostOk: Material; private set
    lateinit var ghostBad: Material; private set
    lateinit var ghostOff: Material; private set
    lateinit var ghostHint: Material; private set
    lateinit var redFlash: Material; private set
    lateinit var slotGlow: Material; private set

    lateinit var cellShape: ShapeResource; private set
    lateinit var pawnShape: ShapeResource; private set
    lateinit var railShape: ShapeResource; private set
    lateinit var wallShapeH: ShapeResource; private set
    lateinit var wallShapeV: ShapeResource; private set
    lateinit var boardShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            listOf(
                async(io) { r.ringSelect = MeshResource.createTorus(outerRingRadius = 0.0215f, innerRingRadius = 0.0185f).keep() },
                async(io) { r.ringJump = MeshResource.createTorus(outerRingRadius = 0.0165f, innerRingRadius = 0.0135f).keep() },
                async(io) { r.ringCell = MeshResource.createTorus(outerRingRadius = 0.0195f, innerRingRadius = 0.0170f).keep() },
                async(io) { r.ringGoal = MeshResource.createTorus(outerRingRadius = 0.0240f, innerRingRadius = 0.0185f).keep() },
                async(io) { r.dot = MeshResource.createTorus(outerRingRadius = 0.0065f, innerRingRadius = 0.0008f).keep() },
                async(io) { r.unitPlane = MeshResource.createPlane(1f, 1f, 0f).keep() },
                // gate:allow geometry ghost wall: a translucent abstract preview of where the maple wall will stand, not a prop
                async(io) { r.ghost = MeshResource.createBox(Vector3(Layout.WALL_L, Layout.WALL_H, Layout.WALL_T), cornerRadius = 0.001f).keep() },
                // gate:allow geometry red flash shell around a rejected wall (S08 indicator), not a prop
                async(io) { r.flash = MeshResource.createBox(Vector3(Layout.WALL_L + 0.003f, Layout.WALL_H + 0.003f, Layout.WALL_T + 0.003f), cornerRadius = 0.0015f).keep() },
                async(io) { r.cream = unlit(Color(0xFFF5F0E6), 0.95f).keep() },
                async(io) { r.creamFaint = unlit(Color(0xFFF5F0E6), 0.5f).keep() },
                async(io) { r.blue = unlit(Color(0xFF4FA3E0), 0.95f).keep() },
                async(io) { r.amber = unlit(Color(0xFFF2C14E), 0.95f).keep() },
                async(io) { r.red = unlit(Color(0xFFC8463A), 0.95f).keep() },
                async(io) { r.faint = unlit(Color(0xFF9CC7EE), 0.55f).keep() },
                async(io) { r.gold = unlit(Color(0xFFF2C14E), 0.9f).keep() },
                async(io) { r.bandGold = unlit(Color(0xFFF2C14E), 0.85f).keep() },
                async(io) { r.bandBlue = unlit(Color(0xFF4FA3E0), 0.85f).keep() },
                async(io) { r.ghostOk = unlit(Color(0xFFF5EBD2), 0.45f).keep() },
                async(io) { r.ghostBad = unlit(Color(0xFFC8463A), 0.5f).keep() },
                async(io) { r.ghostOff = unlit(Color(0xFF9A9A9A), 0.35f).keep() },
                async(io) { r.ghostHint = unlit(Color(0xFFF2C14E), 0.45f).keep() },
                async(io) { r.redFlash = unlit(Color(0xFFC8463A), 0.7f).keep() },
                async(io) { r.slotGlow = unlit(Color(0xFFF5F0E6), 0.22f).keep() },
                async(io) { r.cellShape = ShapeResource.createBox(Vector3(Layout.CELL, 0.004f, Layout.CELL)).keep() },
                async(io) { r.pawnShape = ShapeResource.createBox(Vector3(Layout.PAWN_D, Layout.PAWN_H, Layout.PAWN_D)).keep() },
                async(io) { r.railShape = ShapeResource.createBox(Vector3(Layout.BOARD_W, 0.03f, Layout.RAIL)).keep() },
                async(io) { r.wallShapeH = ShapeResource.createBox(Vector3(Layout.WALL_L, Layout.WALL_H, Layout.WALL_T)).keep() },
                async(io) { r.wallShapeV = ShapeResource.createBox(Vector3(Layout.WALL_T, Layout.WALL_H, Layout.WALL_L)).keep() },
                async(io) { r.boardShape = ShapeResource.createBox(Vector3(Layout.BOARD_W, Layout.BOARD_T, Layout.BOARD_D)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
