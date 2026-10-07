package app.drokpo.android.features.communityhome

import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.components.GroupedButtonRow
import app.drokpo.android.ui.components.GroupedForm
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedTextField
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.components.SegmentedPicker
import app.drokpo.android.ui.components.Spinner
import app.drokpo.android.ui.components.rememberPhotoPicker
import app.drokpo.android.ui.theme.DrokpoTheme
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.TimeZone

private val TextKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
private val UrlKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
)

/**
 * Port of CommunityPostComposerView (CONTRACT §B.8). Self-contained: renders
 * its own FullScreenCover with a fresh model per presentation. On success:
 * awaits [onSaved] (the caller reloads), then [onDismissRequest].
 */
@Composable
fun CommunityPostComposerSheet(onSaved: suspend () -> Unit, onDismissRequest: () -> Unit) {
    FullScreenCover(onDismissRequest = onDismissRequest) {
        val model = viewModel { CommunityPostComposerModel() }
        val currentOnSaved by rememberUpdatedState(onSaved)
        val currentOnDismiss by rememberUpdatedState(onDismissRequest)
        val pickPhoto = rememberPhotoPicker(maxItems = 1) { uris -> uris.firstOrNull()?.let(model::onPhotoPicked) }
        CommunityPostComposerContent(
            draft = model.draft,
            isSaving = model.isSaving,
            canSave = model.canSave,
            errorMessage = model.errorMessage,
            earliestEventDate = model::earliestEventDate,
            onDraftChange = model::updateDraft,
            onEventDateChange = model::setEventDate,
            onChoosePhoto = pickPhoto,
            onRemovePhoto = model::removePhoto,
            onPost = { model.save(onSaved = { currentOnSaved() }, onDone = { currentOnDismiss() }) },
            onCancel = onDismissRequest,
            onDismissError = model::dismissError,
        )
    }
}

@Composable
internal fun CommunityPostComposerContent(
    draft: CommunityPostDraft,
    isSaving: Boolean,
    canSave: Boolean,
    errorMessage: String?,
    earliestEventDate: () -> Instant,
    onDraftChange: (CommunityPostDraft) -> Unit,
    onEventDateChange: (Instant) -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onPost: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val kind = draft.kind
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "New post",
                navIcon = NavIcon.Close,
                onNavIcon = onCancel,
                containerColor = colors.groupedBackground,
                actions = {
                    if (isSaving) {
                        Spinner(Modifier.padding(horizontal = 16.dp))
                    } else {
                        PlainTextButton("Post", onClick = onPost, enabled = canSave, emphasized = true)
                    }
                },
            )
        },
        containerColor = colors.groupedBackground,
    ) { padding ->
        GroupedForm(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .imePadding(),
            contentPadding = PaddingValues(top = 16.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            GroupedSection(header = "Post type") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SegmentedPicker(
                        options = PostKind.entries,
                        selected = kind,
                        onSelect = { onDraftChange(draft.copy(kind = it)) },
                        label = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            GroupedSection(
                header = when (kind) {
                    PostKind.Poll -> "Question"
                    PostKind.Event -> "Event"
                    else -> "Post"
                },
            ) {
                GroupedTextField(
                    value = draft.title,
                    onValueChange = { onDraftChange(draft.copy(title = it)) },
                    placeholder = when (kind) {
                        PostKind.Poll -> "Ask a question"
                        PostKind.Event -> "Event name"
                        else -> "Title"
                    },
                    keyboardOptions = TextKeyboard,
                )
                if (kind != PostKind.Poll) {
                    GroupedTextField(
                        value = draft.body,
                        onValueChange = { onDraftChange(draft.copy(body = it)) },
                        placeholder = "Description",
                        singleLine = false,
                        minLines = 3,
                        maxLines = 8,
                        keyboardOptions = TextKeyboard,
                    )
                }
            }

            if (kind == PostKind.Event) {
                GroupedSection(header = "Event details") {
                    EventDateRow(
                        date = draft.eventDate,
                        earliest = earliestEventDate,
                        onChange = onEventDateChange,
                    )
                    GroupedTextField(
                        value = draft.eventLocation,
                        onValueChange = { onDraftChange(draft.copy(eventLocation = it)) },
                        placeholder = "Location (optional)",
                        keyboardOptions = TextKeyboard,
                    )
                }
            }

            if (kind == PostKind.Link) {
                GroupedSection(
                    header = "Link",
                    footer = "Members open this in-app when they swipe right on your card.",
                ) {
                    LinkFields(draft, placeholder = "https://…", onDraftChange = onDraftChange)
                }
            }

            if (kind == PostKind.Event) {
                GroupedSection(
                    header = "Registration link",
                    footer = "Optional. Members open this in-app when they swipe right on your card.",
                ) {
                    LinkFields(draft, placeholder = "https://… (optional)", onDraftChange = onDraftChange)
                }
            }

            if (kind == PostKind.Poll) {
                GroupedSection(
                    header = "Options",
                    footer = "2–4 options. Members can change their vote any time.",
                ) {
                    draft.pollOptions.forEach { option ->
                        // Keyed by the draft's stable id, so removing a row never
                        // re-binds a neighbour's text field.
                        key(option.id) {
                            PollOptionRow(
                                text = option.text,
                                removable = draft.canRemovePollOption,
                                onTextChange = { onDraftChange(draft.updatingPollOption(option.id, it)) },
                                onRemove = { onDraftChange(draft.removingPollOption(option.id)) },
                            )
                        }
                    }
                    if (draft.canAddPollOption) {
                        GroupedButtonRow("Add option", onClick = { onDraftChange(draft.addingPollOption()) })
                    }
                }
            }

            GroupedSection(header = "Photo (optional)") {
                draft.pickedImage?.let { image ->
                    PickedImagePreview(image)
                    GroupedButtonRow("Remove photo", onClick = onRemovePhoto, destructive = true)
                }
                GroupedButtonRow(
                    if (draft.pickedImage == null) "Choose photo" else "Replace photo",
                    onClick = onChoosePhoto,
                )
            }
        }
    }
    ErrorAlert(message = errorMessage, onDismiss = onDismissError, title = "Couldn't post")
}

/** The link + button-label pair shared by link posts and events. */
@Composable
private fun LinkFields(
    draft: CommunityPostDraft,
    placeholder: String,
    onDraftChange: (CommunityPostDraft) -> Unit,
) {
    GroupedTextField(
        value = draft.linkUrl,
        onValueChange = { onDraftChange(draft.copy(linkUrl = it)) },
        placeholder = placeholder,
        keyboardOptions = UrlKeyboard,
    )
    GroupedTextField(
        value = draft.ctaLabel,
        onValueChange = { onDraftChange(draft.copy(ctaLabel = it)) },
        placeholder = "Button label (optional, e.g. \"Register\")",
        keyboardOptions = TextKeyboard,
    )
}

@Composable
private fun PollOptionRow(
    text: String,
    removable: Boolean,
    onTextChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = "Poll option",
            modifier = Modifier.weight(1f),
            keyboardOptions = TextKeyboard,
        )
        if (removable) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.RemoveCircle,
                    contentDescription = "Remove option",
                    tint = DrokpoTheme.colors.destructive,
                )
            }
        }
    }
}

/** `Image(uiImage:).resizable().scaledToFit().frame(maxHeight: 160)`. */
@Composable
private fun PickedImagePreview(image: Uri) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = image,
            contentDescription = "Selected photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp),
        )
    }
}

/**
 * `DatePicker("Date & time", selection:, in: Date()..., displayedComponents: [.date, .hourAndMinute])`
 * in its compact style: the label, then the date and the time as tappable
 * grey pills that open the Material date / time pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDateRow(date: Instant, earliest: () -> Instant, onChange: (Instant) -> Unit) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val local = date.atZone(zone)
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    // Follows the system "Use 24-hour format" setting, like the TimePicker
    // below and iOS's DatePicker (a localized FormatStyle would ignore it).
    val is24Hour = DateFormat.is24HourFormat(context)
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormatter = remember(is24Hour) {
        DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(zone) }
    }

    GroupedRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Date & time", modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DatePill(local.format(dateFormatter)) { showDatePicker = true }
            DatePill(timeFormatter.format(Date.from(date))) { showTimePicker = true }
        }
    }

    if (showDatePicker) {
        val today = remember { earliest().atZone(zone).toLocalDate() }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickerMillisFor(date, zone),
            selectableDates = remember(today) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = isSelectableDay(utcTimeMillis, today)

                    override fun isSelectableYear(year: Int): Boolean = year >= today.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                PlainTextButton("OK", onClick = {
                    showDatePicker = false
                    pickerState.selectedDateMillis?.let { millis -> onChange(withPickedDay(date, millis, zone)) }
                })
            },
            dismissButton = { PlainTextButton("Cancel", onClick = { showDatePicker = false }) },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = is24Hour,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                PlainTextButton("OK", onClick = {
                    showTimePicker = false
                    onChange(withPickedTime(date, pickerState.hour, pickerState.minute, zone))
                })
            },
            dismissButton = { PlainTextButton("Cancel", onClick = { showTimePicker = false }) },
            text = { TimePicker(state = pickerState) },
        )
    }
}

/** iOS compact DatePicker segment: label text on a tertiary-fill rounded pill. */
@Composable
private fun DatePill(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = DrokpoTheme.typography.body,
        color = DrokpoTheme.colors.label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DrokpoTheme.colors.tertiaryFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}
