package app.drokpo.android.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.drokpo.android.features.shared.audio.AudioBubbleView
import app.drokpo.android.features.shared.audio.RecorderFailureRow
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.theme.DrokpoTheme

// Group 10 (comments + audio) owns this file. Add CommentsContent states (threads, replies loading,
// empty, composer recording/preview) with Fixtures.comments / replies (CONTRACT.md §E) — render the
// sheet's content full-screen, not the ModalBottomSheet.
val commentsAudioCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("audio.bubbles", "AudioBubbleView — plain + on tint") {
        val voice = Fixtures.comments.first { it.audioUrl != null }
        Scaffold(
            topBar = { DrokpoTopBar("Audio") },
            containerColor = DrokpoTheme.colors.background,
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AudioBubbleView(id = voice.commentId, url = voice.audioUrl!!, durationSec = voice.audioDurationSec ?: 0)
                AudioBubbleView(
                    id = "catalog-tint",
                    url = voice.audioUrl,
                    durationSec = 75,
                    isOnTintBackground = true,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(DrokpoTheme.colors.accent)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    },
    CatalogEntry("audio.recorderfailure", "RecorderFailureRow — mic denied") {
        Scaffold(
            topBar = { DrokpoTopBar("Comments") },
            containerColor = DrokpoTheme.colors.background,
        ) { padding ->
            RecorderFailureRow(
                message = "Microphone access is off. You can turn it on in Settings.",
                onDismiss = {},
                modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            )
        }
    },
)
