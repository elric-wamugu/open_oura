package org.openoura.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.openoura.android.R

/**
 * An icon set on one of Material's expressive shapes — the softly scalloped "cookie" by
 * default. Used to give each section and vital a recognisable mark at a glance.
 */
@Composable
fun ShapedIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 40.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** A section title: sentence case in the primary colour, as Material 3 lists label groups. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.ic_sym_arrow_back), contentDescription = "Back")
    }
}

/**
 * Corner shape for item [index] of a [count]-item segmented group: generous outer corners
 * on the group as a whole, tight inner ones between neighbours, so a run of separate
 * surfaces reads as one block — the Material 3 Expressive list style.
 */
fun segmentShape(index: Int, count: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): Shape {
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner,
        bottomEnd = if (last) outer else inner,
    )
}

/** Gap between the surfaces of a segmented group. */
val SegmentGap = 3.dp
