package io.github.ravenor907.ravendisk.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.ravenor907.ravendisk.core.fs.FsType

/**
 * Raven's palette, taken from the app icon: a night-navy sky, the charcoal body of the stick,
 * the copper raven and the silver plug. Dark only, by design.
 */
object Raven {
    val Night = Color(0xFF020F2A)
    val NightLow = Color(0xFF041B42)
    val Slate = Color(0xFF1C1D21)
    val Iron = Color(0xFF242428)
    val IronHi = Color(0xFF2E2F34)
    val Seam = Color(0xFF3B3D45)
    val Mist = Color(0xFF7C818C)
    val Ash = Color(0xFFAEB0B5)
    val Bone = Color(0xFFF2EFEB)
    val CopperHi = Color(0xFFE38D58)
    val Copper = Color(0xFFCD7545)
    val Danger = Color(0xFFEF5B5B)
    val Warn = Color(0xFFF5B83D)
    val Good = Color(0xFF5FD39A)
    val Ink = Color(0xFF170D07)
    val accent = Brush.horizontalGradient(listOf(CopperHi, Copper))
    val sky = Brush.verticalGradient(listOf(Night, NightLow))

    val brand = TextStyle(color = Ash, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
    val title = TextStyle(color = Bone, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    val h2 = TextStyle(color = Bone, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val section = TextStyle(color = Ash, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    val body = TextStyle(color = Bone, fontSize = 15.sp, lineHeight = 21.sp)
    val hint = TextStyle(color = Ash, fontSize = 14.sp, lineHeight = 19.sp)
    val small = TextStyle(color = Ash, fontSize = 12.5.sp, lineHeight = 17.sp)
    val mono = TextStyle(color = Bone, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
    val big = TextStyle(color = Bone, fontSize = 40.sp, fontWeight = FontWeight.Light)

    /** One color per filesystem family, used in partition maps and legends. */
    fun fsColor(fs: FsType?): Color = when (fs) {
        FsType.FAT12, FsType.FAT16, FsType.FAT32 -> Color(0xFF6FA8FF)
        FsType.EXFAT -> Color(0xFF4FC3A1)
        FsType.NTFS -> Color(0xFF8B7CFF)
        FsType.EXT2, FsType.EXT3, FsType.EXT4 -> Color(0xFFF5B83D)
        FsType.BTRFS -> Color(0xFFE4823A)
        FsType.XFS -> Color(0xFFE06C9F)
        FsType.F2FS -> Color(0xFF9CCC65)
        FsType.SWAP -> Color(0xFF7D8896)
        FsType.LUKS, FsType.BITLOCKER -> Color(0xFFB39DDB)
        FsType.ISO9660, FsType.UDF -> Color(0xFF80CBC4)
        null -> Color(0xFF4A5462)
        else -> Color(0xFF5A6574)
    }
}
