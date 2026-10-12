package com.wasimaster.wmkeyboard.core.stickers.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bot API answers, read the way the importer reads them.
 *
 * Shaped after real `getStickerSet` and `getFile` answers. What is pinned is
 * the three kinds of sticker, the thumbnail under both of its names, and the
 * refusals: a unique id or a file path that could leave the cache folder or
 * the bot's file root never gets through.
 */
class TelegramStickerSetTest {

    private val answer = """
        {"ok":true,"result":{"name":"Animals_by_StickerBot","title":"Animals ","sticker_type":"regular",
        "contains_masks":false,"stickers":[
          {"width":512,"height":512,"emoji":"🐱","set_name":"Animals_by_StickerBot","is_animated":false,
           "is_video":false,"type":"regular","thumbnail":{"file_id":"T1","file_unique_id":"AQADt1","file_size":4000,
           "width":128,"height":128},"file_id":"CAACAgIAAxUAAWb1","file_unique_id":"AgADs1","file_size":30000},
          {"width":512,"height":512,"emoji":"🐶","is_animated":true,"is_video":false,"type":"regular",
           "thumb":{"file_id":"T2","file_unique_id":"AQADt2"},"file_id":"CAACAgIAAxUAAWb2","file_unique_id":"AgADs2"},
          {"width":512,"height":512,"emoji":"🦊","is_animated":false,"is_video":true,"type":"regular",
           "file_id":"CAACAgIAAxUAAWb3","file_unique_id":"AgADs3"},
          {"width":512,"height":512,"emoji":"🐭","file_id":"CAACAgIAAxUAAWb4","file_unique_id":"../../etc"},
          {"width":512,"height":512,"emoji":"🐱","file_id":"CAACAgIAAxUAAWb1x","file_unique_id":"AgADs1"}
        ]}}
    """.trimIndent()

    @Test
    fun `reads the set, its three kinds of sticker and their thumbnails`() {
        val set = TelegramStickerSet.parse(answer)!!
        assertEquals("Animals_by_StickerBot", set.name)
        assertEquals("Animals", set.title)
        assertEquals("regular", set.type)
        assertEquals(listOf("AgADs1", "AgADs2", "AgADs3"), set.stickers.map { it.uniqueId })
        assertEquals(
            listOf(TelegramStickerFormat.STATIC, TelegramStickerFormat.ANIMATED, TelegramStickerFormat.VIDEO),
            set.stickers.map { it.format },
        )
        assertEquals(listOf("🐱", "🐶", "🦊"), set.stickers.map { it.emoji })
        assertEquals("T1", set.stickers[0].thumbId)
        assertEquals("AQADt2", set.stickers[1].thumbUniqueId)
        assertEquals("", set.stickers[2].thumbId)
    }

    @Test
    fun `an error answer or junk is not a set`() {
        assertNull(TelegramStickerSet.parse("""{"ok":false,"error_code":400,"description":"Bad Request: STICKERSET_INVALID"}"""))
        assertNull(TelegramStickerSet.parse("<html>"))
        assertNull(TelegramStickerSet.parse("""{"ok":true,"result":{"name":"../x","stickers":[]}}"""))
        assertEquals(400, TelegramStickerSet.errorCode("""{"ok":false,"error_code":400}"""))
    }

    @Test
    fun `a file path stays under the bot's file root`() {
        assertEquals(
            "stickers/file_12.webp",
            TelegramStickerSet.filePath("""{"ok":true,"result":{"file_id":"x","file_path":"stickers/file_12.webp"}}"""),
        )
        assertNull(TelegramStickerSet.filePath("""{"ok":true,"result":{"file_path":"../bot/getMe"}}"""))
        assertNull(TelegramStickerSet.filePath("""{"ok":true,"result":{"file_path":"/etc/passwd"}}"""))
        assertNull(TelegramStickerSet.filePath("""{"ok":true,"result":{"file_id":"x"}}"""))
    }

    @Test
    fun `set names and bot tokens are checked for shape`() {
        assertTrue(TelegramStickerSet.isSetName("Animals_by_StickerBot"))
        assertFalse(TelegramStickerSet.isSetName(""))
        assertFalse(TelegramStickerSet.isSetName("a/b"))
        assertFalse(TelegramStickerSet.isSetName("a".repeat(65)))

        val token = "123456789:AAE3f9X_abcdefghijklmnopqrstuvwxyz0"
        assertEquals(token, TelegramStickerSet.tokenOrNull("  $token \n"))
        assertNull(TelegramStickerSet.tokenOrNull("123456789"))
        assertNull(TelegramStickerSet.tokenOrNull("abc:AAE3f9X_abcdefghijklmnopqrstuvwxyz0"))
        assertNull(TelegramStickerSet.tokenOrNull("123456789:short"))
        assertNull(TelegramStickerSet.tokenOrNull("123456789:AAE3f9X/abcdefghijklmnopqrstuvwxyz0"))
    }
}
