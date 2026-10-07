package app.drokpo.android.features.communityhome

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.MainTab
import app.drokpo.android.TabReselectEffect
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.Photo
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.components.GroupedDefaults
import app.drokpo.android.ui.components.GroupedForm
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedTextField
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.components.Spinner
import app.drokpo.android.ui.components.rememberPhotoPicker
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.launch

/** iOS `TextField` defaults: sentence capitalisation, autocorrect on. */
private val TextKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

/** `.textInputAutocapitalization(.never).autocorrectionDisabled()` for URLs. */
private val UrlKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
)

/** `.textInputAutocapitalization(.never).autocorrectionDisabled()` for e-mail addresses. */
private val EmailKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Email,
)

/** Social handles: no capitalisation, no autocorrect. */
private val HandleKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

/**
 * Port of CommunityProfileEditorView (CONTRACT §B.8): the community account's
 * Profile tab root ("Community"). Tab root without a NavHost, so its model
 * lives in the session scope and survives tab switches (typed edits too).
 */
@Composable
fun CommunityProfileEditorScreen(modifier: Modifier = Modifier) {
    val model = viewModel { CommunityProfileEditorModel() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val pickPhoto = rememberPhotoPicker(maxItems = 1) { uris -> uris.firstOrNull()?.let(model::addPhoto) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Tapping the already-selected Profile tab scrolls the form back to the top.
    TabReselectEffect(MainTab.Profile) { scope.launch { scrollState.animateScrollTo(0) } }

    CommunityProfileEditorContent(
        state = model.uiState,
        onFieldsChange = model::updateFields,
        onSave = model::save,
        onOpenSettings = { showSettings = true },
        onAddPhoto = pickPhoto,
        onDeletePhoto = model::deletePhoto,
        onRefresh = model::refresh,
        onDismissError = model::dismissError,
        modifier = modifier,
        scrollState = scrollState,
    )

    if (showSettings) {
        FullScreenCover(onDismissRequest = { showSettings = false }) {
            CommunitySettingsScreen(onDismiss = { showSettings = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityProfileEditorContent(
    state: CommunityEditorUiState,
    onFieldsChange: (CommunityEditorFields) -> Unit,
    onSave: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddPhoto: () -> Unit,
    onDeletePhoto: (Photo) -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = DrokpoTheme.colors
    val fields = state.fields
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "Community",
                containerColor = colors.groupedBackground,
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = colors.accent)
                    }
                },
                actions = { SaveAction(state, onSave) },
            )
        },
        containerColor = colors.groupedBackground,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            GroupedForm(
                modifier = Modifier.imePadding(),
                scrollState = scrollState,
                contentPadding = PaddingValues(top = 16.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            ) {
                if (!state.isVerified) {
                    // iOS: a Form section with zero row insets and a clear background,
                    // so the banner's own 16dp margin sits inside the section's.
                    PendingVerificationBanner(Modifier.padding(horizontal = GroupedDefaults.SectionMargin))
                }

                PhotosSection(state, onAddPhoto, onDeletePhoto)

                GroupedSection(
                    header = "About",
                    footerContent = state.saveBlocker?.takeIf { state.isDirty }?.let { blocker ->
                        { Text(blocker, color = colors.destructive) }
                    },
                ) {
                    GroupedTextField(fields.name, { onFieldsChange(fields.copy(name = it)) }, "Name", keyboardOptions = TextKeyboard)
                    GroupedTextField(
                        value = fields.description,
                        onValueChange = { onFieldsChange(fields.copy(description = it)) },
                        placeholder = "Description",
                        singleLine = false,
                        minLines = 3,
                        maxLines = 8,
                        keyboardOptions = TextKeyboard,
                    )
                }

                GroupedSection(header = "Contact info") {
                    GroupedTextField(fields.website, { onFieldsChange(fields.copy(website = it)) }, "Website (https://…)", keyboardOptions = UrlKeyboard)
                    GroupedTextField(fields.phone, { onFieldsChange(fields.copy(phone = it)) }, "Phone", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.email, { onFieldsChange(fields.copy(email = it)) }, "Email", keyboardOptions = EmailKeyboard)
                }

                GroupedSection(header = "Social media") {
                    SocialFieldRow("Instagram", fields.instagram) { onFieldsChange(fields.copy(instagram = it)) }
                    SocialFieldRow("YouTube", fields.youtube) { onFieldsChange(fields.copy(youtube = it)) }
                    SocialFieldRow("TikTok", fields.tiktok) { onFieldsChange(fields.copy(tiktok = it)) }
                    SocialFieldRow("Facebook", fields.facebook) { onFieldsChange(fields.copy(facebook = it)) }
                }

                GroupedSection(header = "Person to contact") {
                    GroupedTextField(fields.contactName, { onFieldsChange(fields.copy(contactName = it)) }, "Name", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.contactRole, { onFieldsChange(fields.copy(contactRole = it)) }, "Role", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.contactPhone, { onFieldsChange(fields.copy(contactPhone = it)) }, "Phone", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.contactEmail, { onFieldsChange(fields.copy(contactEmail = it)) }, "Email", keyboardOptions = EmailKeyboard)
                }

                GroupedSection(header = "Address") {
                    GroupedTextField(fields.line1, { onFieldsChange(fields.copy(line1 = it)) }, "Street address", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.city, { onFieldsChange(fields.copy(city = it)) }, "City", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.state, { onFieldsChange(fields.copy(state = it)) }, "State / province", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.country, { onFieldsChange(fields.copy(country = it)) }, "Country", keyboardOptions = TextKeyboard)
                    GroupedTextField(fields.postalCode, { onFieldsChange(fields.copy(postalCode = it)) }, "Postal code", keyboardOptions = TextKeyboard)
                }

                GroupedSection(header = "Status") {
                    StatusRow(
                        title = "Verification",
                        value = if (state.isVerified) "Verified" else "Pending",
                        valueColor = if (state.isVerified) colors.green else colors.orange,
                    )
                    StatusRow(
                        title = "Members",
                        value = "${state.community?.memberCount ?: 0}",
                        valueColor = colors.secondaryLabel,
                    )
                }
            }
            if (state.isWorking) LoadingState()
        }
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

/** The confirmation-action slot: "Saved ✓" (green, 2 s) → spinner while saving → "Save". */
@Composable
private fun SaveAction(state: CommunityEditorUiState, onSave: () -> Unit) {
    val colors = DrokpoTheme.colors
    when {
        state.justSaved -> Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.green, modifier = Modifier.size(18.dp))
            Text("Saved", style = DrokpoTheme.typography.body, color = colors.green)
        }
        state.isSaving -> Spinner(Modifier.padding(horizontal = 16.dp))
        else -> PlainTextButton("Save", onClick = onSave, enabled = state.saveBlocker == null, emphasized = true)
    }
}

/** Horizontal strip of 90×120 tiles: each with an X delete, then "+" while under the cap. */
@Composable
private fun PhotosSection(
    state: CommunityEditorUiState,
    onAddPhoto: () -> Unit,
    onDeletePhoto: (Photo) -> Unit,
) {
    GroupedSection(header = "Photos", footer = "The first photo is used as your logo across the app.") {
        Box(Modifier.padding(GroupedDefaults.RowPadding)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.photos.forEach { photo ->
                    PhotoTile(photo = photo, onDelete = { onDeletePhoto(photo) })
                }
                if (state.canAddPhoto) AddPhotoTile(onAddPhoto)
            }
        }
    }
}

private val TileShape = RoundedCornerShape(10.dp)

@Composable
private fun PhotoTile(photo: Photo, onDelete: () -> Unit) {
    Box(
        Modifier
            .size(width = 90.dp, height = 120.dp)
            .clip(TileShape),
    ) {
        RemotePhotoView(photo = photo, modifier = Modifier.fillMaxSize())
        // The visual is iOS's 22pt xmark.circle.fill (.white on .black.opacity(0.6)),
        // inset 4 from the corner; the tap target around it is 44dp so a delete
        // isn't a fiddly 22dp poke (the tile is only 90dp wide, so not the full 48).
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(44.dp)
                .clickable(role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Delete photo",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun AddPhotoTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 90.dp, height = 120.dp)
            .clip(TileShape)
            .background(DrokpoTheme.colors.quaternaryLabel)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Add photo", tint = DrokpoTheme.colors.accent)
    }
}

/** `HStack { Text(title); TextField("handle", …).multilineTextAlignment(.trailing) }`. */
@Composable
private fun SocialFieldRow(title: String, value: String, onValueChange: (String) -> Unit) {
    val colors = DrokpoTheme.colors
    val textStyle = DrokpoTheme.typography.body.copy(color = colors.label, textAlign = TextAlign.End)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GroupedDefaults.RowMinHeight)
            .padding(GroupedDefaults.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = DrokpoTheme.typography.body, color = colors.label)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = textStyle,
            singleLine = true,
            keyboardOptions = HandleKeyboard,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text("handle", style = textStyle, color = colors.placeholderText, modifier = Modifier.fillMaxWidth())
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun StatusRow(title: String, value: String, valueColor: Color) {
    GroupedRow {
        Text(title, modifier = Modifier.weight(1f))
        Text(value, color = valueColor)
    }
}
