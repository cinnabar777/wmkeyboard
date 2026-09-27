package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The image guide's numbers (issue #397): a template keeps the slot's shape,
 * grows to what the keyboard keeps of a picture and never shrinks, and the
 * background covers the board and the band under the navigation bar.
 */
class ThemeAssetSizesTest {

    private fun key(face: AssetFace, left: Float, top: Float, w: Float, h: Float) =
        KeyFaceBox(label = "", face = face, left = left, top = top, width = w, height = h)

    private val board = BoardGeometry(
        widthPx = 1080,
        heightPx = 800,
        density = 3f,
        keys = listOf(
            key(AssetFace.LETTER, 8f, 150f, 92f, 126f),
            key(AssetFace.LETTER, 116f, 150f, 92f, 126f),
            key(AssetFace.LETTER, 224f, 150f, 92f, 126f),
            // An odd one out: the typical letter must not be this.
            key(AssetFace.LETTER, 332f, 150f, 60f, 126f),
            key(AssetFace.MODIFIER, 8f, 300f, 146f, 126f),
            key(AssetFace.ENTER, 926f, 600f, 146f, 126f),
            key(AssetFace.SPACE, 300f, 600f, 480f, 126f),
            key(AssetFace.SPACE, 300f, 600f, 200f, 126f),
        ),
        navBleedPx = 72,
    )

    @Test
    fun fitUpKeepsShapeAndNeverShrinks() {
        assertEquals(128 to 256, fitUp(50, 100, 256, 256))
        assertEquals(1024 to 256, fitUp(400, 100, 1024, 320))
        assertEquals(2000 to 900, fitUp(2000, 900, 256, 256))
    }

    @Test
    fun typicalFaceIsTheMostCommonAndSpaceIsTheWidest() {
        assertEquals(92f, board.typical(AssetFace.LETTER)!!.width)
        assertEquals(480f, board.typical(AssetFace.SPACE)!!.width)
        assertEquals(150f, board.keysTopPx)
    }

    @Test
    fun backgroundCoversTheBoardAndTheNavigationBand() {
        val background = assetSizes(board, popupDp = 60).first { it.slot == AssetSlot.BACKGROUND }
        assertEquals(1080, background.templateW)
        assertEquals(872, background.templateH)
    }

    @Test
    fun keyTemplatesGrowToTheDecodeLimits() {
        val sizes = assetSizes(board, popupDp = 60).associateBy { it.slot }
        val letter = sizes.getValue(AssetSlot.KEY)
        assertEquals(92 to 126, letter.screenW to letter.screenH)
        assertEquals(ASSET_KEY_MAX_PX, letter.templateH)
        val space = sizes.getValue(AssetSlot.SPACE)
        assertEquals(ASSET_SPACE_MAX_W, space.templateW)
        val popup = sizes.getValue(AssetSlot.POPUP)
        assertEquals(92 + 24, popup.screenW)
        assertEquals(180, popup.screenH)
    }
}
