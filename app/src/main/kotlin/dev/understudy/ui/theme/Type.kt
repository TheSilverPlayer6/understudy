package dev.understudy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Deliberately close to the Material 3 defaults.
 *
 * The two deviations are purposeful: a monospace style for paths, package names, hashes and
 * shell commands (proportional type makes `com.foo.bar` hard to read and impossible to
 * compare), and slightly tighter body line height so dense file listings stay scannable.
 */
val Typography = Typography().run {
    copy(
        bodyMedium = bodyMedium.copy(lineHeight = 20.sp),
        bodySmall = bodySmall.copy(lineHeight = 16.sp),
    )
}

/** For paths, package names, fingerprints and anything else the user may need to compare. */
val MonoTypography = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

/** Monospace but sized for shell command blocks, which are read line by line. */
val CommandTypography = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
)
