package app.drokpo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * Circular profile photo (`RemotePhotoView(…).frame(width: s, height: s)
 * .clipShape(Circle())`): 56 in like/chat rows, 64 in "New matches", 140 on
 * the match overlay. With no photo it shows the person's initials on the
 * grey fill, or the person glyph when there's no name either.
 */
@Composable
fun Avatar(
    photo: Photo?,
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val initials = remember(name) { initialsOf(name) }
    val circle = modifier
        .size(size)
        .clip(CircleShape)
    when {
        photo != null -> RemotePhotoView(photo = photo, modifier = circle, contentDescription = name)
        initials != null -> Box(circle.background(DrokpoTheme.colors.fill), contentAlignment = Alignment.Center) {
            Text(
                initials,
                color = DrokpoTheme.colors.secondaryLabel,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        else -> RemotePhotoView(photo = null, modifier = circle)
    }
}

/** Up to two initials, code-point aware so non-Latin (e.g. Tibetan) names don't split a character. */
internal fun initialsOf(name: String?): String? {
    val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
    if (words.isEmpty()) return null
    return words.take(2).joinToString("") { word ->
        String(Character.toChars(word.codePointAt(0))).uppercase()
    }
}

@DrokpoPreviews
@Composable
private fun AvatarPreview() {
    DrokpoTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(photo = null, name = "Tenzin Dolma")
            Avatar(photo = null, name = "ཀུན་དགའ་")
            Avatar(photo = null, name = null)
            Avatar(photo = Photo(storagePath = "users/preview/photos/1.jpg"), name = "Pema", size = 64.dp)
        }
    }
}
