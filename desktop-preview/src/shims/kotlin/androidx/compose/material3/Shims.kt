package androidx.compose.material3

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material3 1.2+ API used by the app; Compose Multiplatform 1.5 only has Divider. */
@Composable
fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = Color.Unspecified) =
    Divider(modifier, thickness, color)
