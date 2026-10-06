package app.drokpo.android.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.drokpo.android.features.chats.ChatStore
import app.drokpo.android.features.chats.LocalChatStore
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ListDivider
import app.drokpo.android.ui.components.RoundedTextField
import app.drokpo.android.ui.components.ScopedViewModels
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Debug-only screen catalog (CONTRACT.md §E): every screen rendered with fixture data, for visual QA
 * without signing in.
 *
 * ```
 * adb shell am start -n app.drokpo.android/.catalog.CatalogActivity                      # searchable list
 * adb shell am start -n app.drokpo.android/.catalog.CatalogActivity --es entry <id>      # one entry, full screen
 * adb shell am start -n app.drokpo.android/.catalog.CatalogActivity --es entry <id> --ez dark true
 * ```
 *
 * Every entry renders inside [DrokpoTheme] with an idle (never started) [ChatStore] provided as
 * [LocalChatStore], so components that need it (ShareSheet, ShareButton) work. Back from an entry
 * opened in the list returns to the list; back from an entry launched with `--es entry` exits.
 */
class CatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val requestedEntry = intent.getStringExtra(EXTRA_ENTRY)
        val requestedDark: Boolean? =
            if (intent.hasExtra(EXTRA_DARK)) intent.getBooleanExtra(EXTRA_DARK, false) else null

        setContent {
            val systemDark = isSystemInDarkTheme()
            var dark by rememberSaveable { mutableStateOf(requestedDark ?: systemDark) }
            var openId by rememberSaveable { mutableStateOf(requestedEntry) }
            var openedFromList by rememberSaveable { mutableStateOf(false) }
            val chatStore = remember { ChatStore() }

            DrokpoTheme(dark = dark) {
                CompositionLocalProvider(LocalChatStore provides chatStore) {
                    val entry = openId?.let { id -> allCatalogEntries.firstOrNull { it.id == id } }
                    if (entry != null) {
                        BackHandler(enabled = openedFromList) {
                            openId = null
                            openedFromList = false
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(DrokpoTheme.colors.background),
                        ) {
                            // A fresh ViewModel scope per entry, cleared when it closes.
                            key(entry.id) { ScopedViewModels { entry.content() } }
                        }
                    } else {
                        CatalogList(
                            entries = allCatalogEntries,
                            missingId = openId,
                            dark = dark,
                            onToggleDark = { dark = !dark },
                            onOpen = { id ->
                                openId = id
                                openedFromList = true
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_DARK = "dark"
    }
}

@Composable
private fun CatalogList(
    entries: List<CatalogEntry>,
    missingId: String?,
    dark: Boolean,
    onToggleDark: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    var query by rememberSaveable { mutableStateOf(missingId.orEmpty()) }
    val filtered = remember(entries, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) entries else entries.filter { q in it.id || q in it.title.lowercase() }
    }

    Scaffold(
        topBar = {
            DrokpoTopBar(
                title = "Catalog (${entries.size})",
                actions = {
                    IconButton(onClick = onToggleDark) {
                        Icon(
                            if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (dark) "Light theme" else "Dark theme",
                        )
                    }
                },
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            RoundedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search ids and titles",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            if (missingId != null) {
                CatalogNotice("No catalog entry \"$missingId\" — showing matches.")
            }
            if (duplicateCatalogIds.isNotEmpty()) {
                CatalogNotice("Duplicate ids: ${duplicateCatalogIds.joinToString()}")
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                val groups = filtered.groupBy { it.group }
                groups.forEach { (group, groupEntries) ->
                    item(key = "header-$group") {
                        Text(
                            group.uppercase(),
                            style = typography.footnote,
                            color = colors.secondaryLabel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.groupedBackground)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(groupEntries, key = { "entry-${it.id}" }) { entry ->
                        CatalogRow(entry, onClick = { onOpen(entry.id) })
                        ListDivider()
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No matches.",
                            style = typography.subheadline,
                            color = colors.secondaryLabel,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun CatalogRow(entry: CatalogEntry, onClick: () -> Unit) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = typography.body, color = colors.label)
            Text(entry.id, style = typography.caption, color = colors.secondaryLabel)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.tertiaryLabel)
    }
}

@Composable
private fun CatalogNotice(text: String) {
    Text(
        text,
        style = DrokpoTheme.typography.footnote.bold(),
        color = DrokpoTheme.colors.orange,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
