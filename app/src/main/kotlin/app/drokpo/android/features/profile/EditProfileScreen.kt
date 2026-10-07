package app.drokpo.android.features.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.features.onboarding.ProfileQuestionFields
import app.drokpo.android.features.onboarding.rememberLocationFetcher
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedCheckmarkRow
import app.drokpo.android.ui.components.GroupedDefaults
import app.drokpo.android.ui.components.GroupedForm
import app.drokpo.android.ui.components.GroupedPickerRow
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedTextField
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.components.Spinner
import app.drokpo.android.ui.components.openAppSettings
import app.drokpo.android.ui.theme.DrokpoTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Port of EditProfileView, shown by ProfileScreen in a FullScreenCover.
 * [onSaved] runs after a successful PATCH (ProfileScreen refreshes the
 * session profile), then the editor dismisses itself through [onDismiss].
 */
@Composable
internal fun EditProfileScreen(profile: Profile, onSaved: suspend () -> Unit, onDismiss: () -> Unit) {
    val model = viewModel { EditProfileModel(profile = profile, onSaved = onSaved) }
    val locationFetcher = rememberLocationFetcher()
    val context = LocalContext.current
    val state = model.state
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state.finished) {
        if (state.finished) currentOnDismiss()
    }
    EditProfileContent(
        state = state,
        onEdit = { transform -> model.updateForm(transform(model.state.form)) },
        onUpdateLocation = {
            model.refreshLocation(
                request = { locationFetcher.requestLocation() },
                // LocationFetcher.isDenied only covers "the system won't ask again". On iOS a
                // declined prompt is `.denied` straight away, so any no-fix without the
                // permission gets the Settings copy; a granted-but-no-fix stays "couldn't get".
                isDenied = { locationFetcher.isDenied || !context.hasLocationPermission() },
            )
        },
        onOpenSettings = { context.openAppSettings() },
        onSave = model::save,
        onCancel = onDismiss,
        onDismissError = model::dismissError,
    )
}

/** Coarse or fine location is granted (Android 12+ lets people grant only approximate). */
private fun Context.hasLocationPermission(): Boolean =
    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Stateless editor form. [onEdit] receives a transform of the *current* form,
 * so quick successive edits never apply to a stale copy.
 */
@Composable
internal fun EditProfileContent(
    state: EditProfileUiState,
    onEdit: ((EditProfileForm) -> EditProfileForm) -> Unit,
    onUpdateLocation: () -> Unit,
    onOpenSettings: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyShowDatePicker: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    val form = state.form
    var showDatePicker by rememberSaveable { mutableStateOf(initiallyShowDatePicker) }

    Scaffold(
        modifier = modifier,
        containerColor = colors.groupedBackground,
        topBar = {
            DrokpoTopBar(
                title = "Edit profile",
                navIcon = NavIcon.Close,
                onNavIcon = onCancel,
                containerColor = colors.groupedBackground,
                actions = {
                    PlainTextButton("Save", onClick = onSave, enabled = state.canSave, emphasized = true)
                },
            )
        },
    ) { padding ->
        GroupedForm(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp + padding.calculateBottomPadding()),
        ) {
            AboutSection(form = form, onEdit = onEdit, onPickBirthday = { showDatePicker = true })
            LocationSection(
                isLocating = state.isLocating,
                status = state.locationStatus,
                denied = state.locationDenied,
                onUpdateLocation = onUpdateLocation,
                onOpenSettings = onOpenSettings,
            )
            GroupedSection(header = "Socials") {
                SocialFieldRow("Instagram", form.instagram) { value -> onEdit { it.copy(instagram = value) } }
                SocialFieldRow("YouTube", form.youtube) { value -> onEdit { it.copy(youtube = value) } }
                SocialFieldRow("TikTok", form.tiktok) { value -> onEdit { it.copy(tiktok = value) } }
            }
            GroupedSection(header = "Languages") {
                Vocabulary.languages.forEach { language ->
                    GroupedCheckmarkRow(
                        title = language,
                        checked = language in form.languages,
                        onClick = { onEdit { it.toggleLanguage(language) } },
                    )
                }
            }
            GroupedSection(header = "Interests") {
                Vocabulary.interests.forEach { interest ->
                    GroupedCheckmarkRow(
                        title = interest,
                        checked = interest in form.interests,
                        onClick = { onEdit { it.toggleInterest(interest) } },
                    )
                }
            }
            ProfileQuestionFields(
                answers = form.answers,
                onAnswerChange = { key, value -> onEdit { it.answering(key, value) } },
            )
            DiscoverySection(form = form, onEdit = onEdit)
        }
    }

    if (showDatePicker) {
        BirthdayPickerDialog(
            selected = form.birthday,
            onPick = { date -> onEdit { it.copy(birthday = date) } },
            onDismiss = { showDatePicker = false },
        )
    }

    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError, title = "Couldn't save")
}

@Composable
private fun AboutSection(
    form: EditProfileForm,
    onEdit: ((EditProfileForm) -> EditProfileForm) -> Unit,
    onPickBirthday: () -> Unit,
) {
    GroupedSection(header = "About") {
        GroupedTextField(
            value = form.displayName,
            onValueChange = { value -> onEdit { it.copy(displayName = value) } },
            placeholder = "Name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        GroupedTextField(
            value = form.bio,
            onValueChange = { value -> onEdit { it.copy(bio = value) } },
            placeholder = "Bio",
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        GroupedPickerRow(
            title = "Gender",
            options = listOf("") + Vocabulary.genders,
            selected = form.gender,
            onSelect = { value -> onEdit { it.copy(gender = value) } },
            optionLabel = { if (it.isEmpty()) "Not set" else capitalizedWords(it) },
        )
        BirthdayRow(birthday = form.birthday, onClick = onPickBirthday)
        GroupedTextField(
            value = form.occupation,
            onValueChange = { value -> onEdit { it.copy(occupation = value) } },
            placeholder = "Occupation",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        )
        GroupedPickerRow(
            title = "Education",
            options = listOf("") + form.educationOptions,
            selected = form.education,
            onSelect = { value -> onEdit { it.copy(education = value) } },
            optionLabel = { it.ifEmpty { "Not set" } },
        )
        GroupedPickerRow(
            title = "Region",
            options = listOf("") + form.regionOptions,
            selected = form.region,
            onSelect = { value -> onEdit { it.copy(region = value) } },
            optionLabel = { it.ifEmpty { "Not set" } },
        )
    }
}

/** `DatePicker("Birthday", displayedComponents: .date)` in compact style: label + date capsule. */
@Composable
private fun BirthdayRow(birthday: LocalDate, onClick: () -> Unit) {
    val colors = DrokpoTheme.colors
    val formatted = remember(birthday) { birthday.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    GroupedRow(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        trailing = {
            Text(
                formatted,
                color = colors.label,
                modifier = Modifier
                    .background(colors.tertiaryFill, RoundedCornerShape(6.dp))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        },
    ) {
        Text("Birthday")
    }
}

/**
 * The calendar behind the Birthday row. Like iOS's `in: ...now − 18 years`,
 * nothing after the date 18 years ago (device calendar) can be picked. The
 * picker speaks UTC-midnight millis, which map 1:1 to the stored `dob` date.
 */
@Composable
private fun BirthdayPickerDialog(selected: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val latest = remember { LocalDate.now(ZoneId.systemDefault()).minusYears(18) }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1900..latest.year,
        selectableDates = remember(latest) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDate(utcTimeMillis) <= latest

                override fun isSelectableYear(year: Int): Boolean = year <= latest.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            PlainTextButton(
                "OK",
                onClick = {
                    pickerState.selectedDateMillis?.let { onPick(utcDate(it)) }
                    onDismiss()
                },
                emphasized = true,
            )
        },
        dismissButton = { PlainTextButton("Cancel", onClick = onDismiss) },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun utcDate(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun LocationSection(
    isLocating: Boolean,
    status: String?,
    denied: Boolean,
    onUpdateLocation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    val tint = if (isLocating) colors.tertiaryLabel else colors.accent
    GroupedSection(
        header = "Location",
        footerContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status ?: EditProfileCopy.LOCATION_FOOTER)
                if (denied) {
                    Text(
                        "Open Settings",
                        color = colors.accent,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSettings),
                    )
                }
            }
        },
    ) {
        GroupedRow(
            onClick = onUpdateLocation,
            enabled = !isLocating,
            leading = { Icon(Icons.Outlined.NearMe, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
            trailing = if (isLocating) ({ Spinner() }) else null,
        ) {
            Text("Update my location", color = tint)
        }
    }
}

/** `HStack { Text(title); TextField("handle").multilineTextAlignment(.trailing) }`, no autocap/autocorrect. */
@Composable
private fun SocialFieldRow(title: String, value: String, onValueChange: (String) -> Unit) {
    val colors = DrokpoTheme.colors
    val textStyle = DrokpoTheme.typography.body.copy(color = colors.label, textAlign = TextAlign.End)
    GroupedRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp)) {
        Text(title)
        Spacer(Modifier.width(16.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = GroupedDefaults.RowMinHeight),
            textStyle = textStyle,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text("handle", style = textStyle, color = colors.placeholderText)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun DiscoverySection(form: EditProfileForm, onEdit: ((EditProfileForm) -> EditProfileForm) -> Unit) {
    GroupedSection(header = "Discovery preferences") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 4.dp)) {
            Text(form.ageLabel)
            RangeSliderRow(
                range = form.ageRange,
                onRangeChange = { range -> onEdit { it.copy(ageRange = range) } },
                bounds = AGE_BOUNDS,
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 4.dp)) {
            Text(form.distanceLabel)
            DrokpoSlider(
                value = form.distanceKm,
                onValueChange = { km -> onEdit { it.copy(distanceKm = snapDistanceKm(km)) } },
                valueRange = DISTANCE_BOUNDS,
                steps = DISTANCE_SLIDER_STEPS,
                contentDescription = "Distance",
            )
        }
    }
}
