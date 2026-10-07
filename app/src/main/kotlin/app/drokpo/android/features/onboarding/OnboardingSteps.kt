package app.drokpo.android.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.ui.components.GroupedCheckmarkRow
import app.drokpo.android.ui.components.GroupedDefaults
import app.drokpo.android.ui.components.GroupedForm
import app.drokpo.android.ui.components.GroupedPickerRow
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedTextField
import app.drokpo.android.ui.components.GroupedToggleRow
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The six step bodies of OnboardingContent (iOS OnboardingFlow's private
// step views). Form steps use GroupedForm on the grouped background; the
// Location and Photos steps are plain VStacks on the screen background.

private val GenderOptions: List<String> = listOf("") + Vocabulary.genders
private val RegionOptions: List<String> = listOf("") + Vocabulary.regions
private val EducationOptions: List<String> = listOf("") + Vocabulary.educationLevels

// MARK: - Basics

@Composable
internal fun BasicsStep(state: OnboardingState, onEdit: (OnboardingEdit) -> Unit) {
    GroupedForm {
        GroupedSection(header = "About you") {
            GroupedTextField(
                value = state.displayName,
                onValueChange = { onEdit(OnboardingEdit.DisplayName(it)) },
                placeholder = "Your name",
                // iOS `.textContentType(.givenName)`.
                modifier = Modifier.semantics { contentType = ContentType.PersonFirstName },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
            )
            DateOfBirthRow(
                dob = state.dob,
                latestAllowed = state.latestAllowedDob,
                onChange = { onEdit(OnboardingEdit.Dob(it)) },
            )
            GroupedPickerRow(
                title = "I am",
                options = GenderOptions,
                selected = state.gender,
                onSelect = { onEdit(OnboardingEdit.Gender(it)) },
                // Stored lowercase ("male"), shown capitalized like Swift's `.capitalized`.
                optionLabel = { if (it.isEmpty()) "Select" else it.capitalizedWords() },
            )
        }
    }
}

/**
 * iOS compact `DatePicker("Date of birth", in: ...latestAllowedDOB)` in a
 * Form: the label with the date in a grey capsule; tapping opens the Material
 * date picker, which can't go past [latestAllowed]. Module-internal so Edit
 * profile's "Birthday" row (same 18+ bound on iOS) can reuse it.
 */
@Composable
internal fun DateOfBirthRow(
    dob: LocalDate,
    latestAllowed: LocalDate,
    onChange: (LocalDate) -> Unit,
    title: String = "Date of birth",
) {
    val colors = DrokpoTheme.colors
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    val formatted = remember(dob, locale) {
        dob.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
    GroupedRow(
        onClick = { showPicker = true },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        trailing = {
            Text(
                text = formatted,
                style = DrokpoTheme.typography.body,
                color = if (showPicker) colors.accent else colors.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.tertiaryFill)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        },
    ) {
        Text(title)
    }
    if (showPicker) {
        DateOfBirthPickerDialog(
            selected = dob,
            latestAllowed = latestAllowed,
            title = title,
            onConfirm = {
                showPicker = false
                onChange(it)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Material date picker bounded to birthdays at least 18 years ago. */
@Composable
internal fun DateOfBirthPickerDialog(
    selected: LocalDate,
    latestAllowed: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Date of birth",
) {
    val colors = DrokpoTheme.colors
    val maxMillis = latestAllowed.toUtcMillis()
    val selectable = remember(maxMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= maxMillis
            override fun isSelectableYear(year: Int): Boolean = year <= latestAllowed.year
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = minOf(selected, latestAllowed).toUtcMillis(),
        yearRange = EARLIEST_BIRTH_YEAR..latestAllowed.year,
        selectableDates = selectable,
    )
    val containerColor = if (colors.isDark) colors.tertiaryBackground else colors.background
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PlainTextButton(
                text = "OK",
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) onConfirm(millis.utcMillisToLocalDate()) else onDismiss()
                },
                enabled = pickerState.selectedDateMillis != null,
                emphasized = true,
            )
        },
        dismissButton = { PlainTextButton(text = "Cancel", onClick = onDismiss) },
        colors = DatePickerDefaults.colors(containerColor = containerColor),
    ) {
        DatePicker(
            state = pickerState,
            title = {
                Text(
                    title,
                    style = DrokpoTheme.typography.subheadline,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
            colors = DatePickerDefaults.colors(containerColor = containerColor),
        )
    }
}

// MARK: - Details

@Composable
internal fun DetailsStep(state: OnboardingState, onEdit: (OnboardingEdit) -> Unit) {
    GroupedForm {
        GroupedSection(header = "Where are you from?") {
            GroupedPickerRow(
                title = "Region",
                options = RegionOptions,
                selected = state.region,
                onSelect = { onEdit(OnboardingEdit.Region(it)) },
                optionLabel = { it.ifEmpty { "Select" } },
            )
        }
        GroupedSection(header = "Languages you speak") {
            Vocabulary.languages.forEach { language ->
                MultiSelectRow(title = language, isSelected = language in state.languages) {
                    onEdit(OnboardingEdit.ToggleLanguage(language))
                }
            }
        }
        GroupedSection(header = "Interests") {
            Vocabulary.interests.forEach { interest ->
                MultiSelectRow(title = interest, isSelected = interest in state.interests) {
                    onEdit(OnboardingEdit.ToggleInterest(interest))
                }
            }
        }
        GroupedSection(header = "About me") {
            GroupedTextField(
                value = state.bio,
                onValueChange = { onEdit(OnboardingEdit.Bio(it)) },
                placeholder = "A few words about yourself…",
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
}

/** iOS `MultiSelectRow`: primary title, accent checkmark when selected; the whole row toggles. */
@Composable
private fun MultiSelectRow(title: String, isSelected: Boolean, onToggle: () -> Unit) {
    GroupedCheckmarkRow(title = title, checked = isSelected, onClick = onToggle)
}

// MARK: - About you

/**
 * Optional get-to-know-you step: work, study, and the friendship prompts.
 * Everything here can be skipped and filled in later from Edit profile.
 */
@Composable
internal fun AboutYouStep(state: OnboardingState, onEdit: (OnboardingEdit) -> Unit) {
    GroupedForm {
        GroupedSection(
            header = "Work & study",
            footer = "All of this is optional — answer what you like. It helps people find things in common with you.",
        ) {
            GroupedTextField(
                value = state.occupation,
                onValueChange = { onEdit(OnboardingEdit.Occupation(it)) },
                placeholder = "Occupation or current job",
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
            )
            GroupedPickerRow(
                title = "Education",
                options = EducationOptions,
                selected = state.education,
                onSelect = { onEdit(OnboardingEdit.Education(it)) },
                optionLabel = { it.ifEmpty { "Select" } },
            )
        }
        ProfileQuestionFields(
            answers = state.answers,
            onAnswerChange = { key, value -> onEdit(OnboardingEdit.Answer(key, value)) },
        )
    }
}

// MARK: - Socials

@Composable
internal fun SocialsStep(state: OnboardingState, onEdit: (OnboardingEdit) -> Unit) {
    GroupedForm {
        GroupedSection(
            header = "Instagram",
            footer = "Optional — adding your Instagram helps new friends see you're a real person.",
        ) {
            InstagramHandleField(
                value = state.instagram,
                onValueChange = { onEdit(OnboardingEdit.Instagram(it)) },
            )
        }
        GroupedSection {
            GroupedToggleRow(
                checked = state.acceptedTerms,
                onCheckedChange = { onEdit(OnboardingEdit.AcceptedTerms(it)) },
            ) {
                Text(
                    "I confirm I am 18 or older and agree to treat other members with respect. Abusive or fake profiles are removed.",
                    style = DrokpoTheme.typography.footnote,
                )
            }
        }
    }
}

/**
 * `HStack { Text("@").secondary; TextField("your_handle") }` — no
 * autocapitalization or autocorrection. The whole row focuses the field.
 */
@Composable
private fun InstagramHandleField(value: String, onValueChange: (String) -> Unit) {
    val colors = DrokpoTheme.colors
    val textStyle = DrokpoTheme.typography.body.copy(color = colors.label)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GroupedDefaults.RowMinHeight),
        textStyle = textStyle,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(GroupedDefaults.RowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("@", style = textStyle, color = colors.secondaryLabel)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("your_handle", style = textStyle, color = colors.placeholderText)
                    }
                    inner()
                }
            }
        },
    )
}

// MARK: - Location

@Composable
internal fun LocationStep(state: OnboardingState) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val saved = state.location != null
    // Centred like the iOS VStack in a full-height frame, but still scrollable
    // at very large font sizes.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            LocationGlyph(saved = saved)
            Text("Share your location", style = typography.title2.bold(), color = colors.label, textAlign = TextAlign.Center)
            Text(
                "Drokpo uses your location to show you people nearby. When you tap Continue, Android will ask whether to share it. If you don't, we'll use the center of your region instead.",
                style = typography.subheadline,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            if (saved) {
                // iOS `Label("Location saved", systemImage: "checkmark.circle.fill")` in the tint.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Location saved", style = typography.body, color = colors.accent)
                }
            }
        }
    }
}

/**
 * SF `location.circle` (outline until a location is saved) /
 * `location.circle.fill` at 64pt in the accent tint: a ringed or filled disc
 * with the location arrow.
 */
@Composable
private fun LocationGlyph(saved: Boolean) {
    val colors = DrokpoTheme.colors
    val disc = if (saved) {
        Modifier.background(colors.accent, CircleShape)
    } else {
        Modifier.border(4.dp, colors.accent, CircleShape)
    }
    Box(
        Modifier
            .size(64.dp)
            .then(disc),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (saved) Icons.Filled.NearMe else Icons.Outlined.NearMe,
            contentDescription = null,
            tint = if (saved) colors.onAccent else colors.accent,
            modifier = Modifier.size(34.dp),
        )
    }
}

// MARK: - Photos

@Composable
internal fun PhotosStep(state: OnboardingState, onEdit: (OnboardingEdit) -> Unit, onAddPhotos: () -> Unit) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    // Picking or removing mid-upload would change what Finish is sending.
    val editable = !state.isSubmitting
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add photos", style = typography.title2.bold(), color = colors.label)
        Text(
            "Add 1–6 photos. The first one is your main photo.",
            style = typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = PhotoTileWidth),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.photos, key = { _, photo -> photo.id }) { index, photo ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PickedPhotoTile(
                        photo = photo,
                        index = index,
                        enabled = editable,
                        onRemove = { onEdit(OnboardingEdit.RemovePhoto(photo.id)) },
                    )
                }
            }
            if (state.canAddPhotos) {
                item(key = "add") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AddPhotoTile(enabled = editable, onClick = onAddPhotos)
                    }
                }
            }
        }
    }
}

private val PhotoTileWidth = 100.dp
private val PhotoTileHeight = 133.dp
private val PhotoTileShape = RoundedCornerShape(10.dp)

@Composable
private fun PickedPhotoTile(photo: PickedPhoto, index: Int, enabled: Boolean, onRemove: () -> Unit) {
    val colors = DrokpoTheme.colors
    var failed by remember(photo.uri) { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = PhotoTileWidth, height = PhotoTileHeight)
            .clip(PhotoTileShape)
            .background(colors.fill),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = if (index == 0) "Main photo" else "Photo ${index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            onState = { failed = it is AsyncImagePainter.State.Error },
        )
        if (failed) {
            Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        RemovePhotoButton(
            enabled = enabled,
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** SF `xmark.circle.fill` styled `.white, .black.opacity(0.6)`, 4dp in from the corner. */
@Composable
private fun RemovePhotoButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    Box(
        modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Remove photo" }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(colors.photoScrimStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = colors.onPhoto,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** The PhotosPicker label: a `.quaternary` rounded tile with an accent "+". */
@Composable
private fun AddPhotoTile(enabled: Boolean, onClick: () -> Unit) {
    val colors = DrokpoTheme.colors
    Box(
        Modifier
            .size(width = PhotoTileWidth, height = PhotoTileHeight)
            .clip(PhotoTileShape)
            .background(colors.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Add photos" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = if (enabled) colors.accent else colors.tertiaryLabel,
            modifier = Modifier.size(28.dp),
        )
    }
}

// MARK: - Helpers

/** Earliest year the birthday picker offers (Material's default lower bound). */
private const val EARLIEST_BIRTH_YEAR = 1900

/** Material's DatePicker speaks UTC-midnight millis for a calendar day. */
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcMillisToLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Swift `String.capitalized`: first letter of each word upper-cased, the rest lower-cased. */
internal fun String.capitalizedWords(): String =
    split(' ').joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.titlecase() }
    }
