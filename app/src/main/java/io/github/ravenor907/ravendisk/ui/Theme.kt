package io.github.ravenor907.ravendisk.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.ravenor907.ravendisk.core.fs.FsType

/**
 * Raven's palette: Handheld Forge's slate-and-iron neutrals with a cold "aurora" accent in place
 * of the forge's molten one. Dark only, by design.
 */
object Raven {
    val Night = Color(0xFF14181D)
    val Slate = Color(0xFF1B2026)
    val Iron = Color(0xFF242A32)
    val IronHi = Color(0xFF2C333C)
    val Seam = Color(0xFF353D47)
    val Mist = Color(0xFF6B7684)
    val Ash = Color(0xFF98A2AE)
    val Bone = Color(0xFFECEFF2)
    val AuroraA = Color(0xFF6FD3FF)
    val AuroraB = Color(0xFF8B7CFF)
    val Danger = Color(0xFFF07167)
    val Warn = Color(0xFFF5B83D)
    val Good = Color(0xFF5FD39A)
    val Ink = Color(0xFF0E1116)
    val aurora = Brush.horizontalGradient(listOf(AuroraA, AuroraB))

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
