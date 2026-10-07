package app.drokpo.android.features.communityonboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.components.GroupedForm
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedTextField
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import coil3.compose.AsyncImage

// The five steps of CommunityOnboardingFlow.swift. Each is a stateless view of
// the form; edits go up as `onFormChange { copy(field = it) }`.

internal typealias FormChange = (CommunityOnboardingForm.() -> CommunityOnboardingForm) -> Unit

/** iOS TextField defaults: sentence capitalisation, autocorrect on. */
private fun textKeyboard(imeAction: ImeAction = ImeAction.Next) =
    KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction)

/** `.textInputAutocapitalization(.never)` + `.autocorrectionDisabled()`, with the keyboard the content type implies. */
private fun literalKeyboard(keyboardType: KeyboardType, imeAction: ImeAction = ImeAction.Next) = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = keyboardType,
    imeAction = imeAction,
)

/** `.textContentType(.telephoneNumber)`. */
private fun phoneKeyboard(imeAction: ImeAction = ImeAction.Next) =
    KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = imeAction)

/** iOS `textContentType` → Android autofill hint. */
private fun Modifier.autofill(type: ContentType): Modifier = semantics { contentType = type }

@Composable
internal fun CommunityBasicsStep(
    form: CommunityOnboardingForm,
    onFormChange: FormChange,
    modifier: Modifier = Modifier,
) {
    GroupedForm(modifier) {
        GroupedSection(
            header = "About your community",
            footer = "This is what members see first — say who you are and what you do.",
        ) {
            GroupedTextField(
                value = form.name,
                onValueChange = { onFormChange { copy(name = it) } },
                placeholder = "Organization or community name",
                keyboardOptions = textKeyboard(),
            )
            // iOS `TextField("Description", axis: .vertical).lineLimit(4...8)`.
            GroupedTextField(
                value = form.communityDescription,
                onValueChange = { onFormChange { copy(communityDescription = it) } },
                placeholder = "Description",
                singleLine = false,
                minLines = 4,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
}

@Composable
internal fun CommunityContactStep(
    form: CommunityOnboardingForm,
    onFormChange: FormChange,
    modifier: Modifier = Modifier,
) {
    GroupedForm(modifier) {
        GroupedSection(
            header = "Contact info",
            footer = "Email is required — verification updates about your community are sent there.",
        ) {
            GroupedTextField(
                value = form.website,
                onValueChange = { onFormChange { copy(website = it) } },
                placeholder = "Website (https://…)",
                keyboardOptions = literalKeyboard(KeyboardType.Uri),
            )
            GroupedTextField(
                value = form.phone,
                onValueChange = { onFormChange { copy(phone = it) } },
                placeholder = "Phone",
                modifier = Modifier.autofill(ContentType.PhoneNumber),
                keyboardOptions = phoneKeyboard(),
            )
            GroupedTextField(
                value = form.email,
                onValueChange = { onFormChange { copy(email = it) } },
                placeholder = "Email (required)",
                modifier = Modifier.autofill(ContentType.EmailAddress),
                keyboardOptions = literalKeyboard(KeyboardType.Email),
            )
        }
        GroupedSection(
            header = "Social media",
            footer = "All optional — add whichever accounts you use.",
        ) {
            SocialHandleRow("Instagram", form.instagram, { onFormChange { copy(instagram = it) } })
            SocialHandleRow("YouTube", form.youtube, { onFormChange { copy(youtube = it) } })
            SocialHandleRow("TikTok", form.tiktok, { onFormChange { copy(tiktok = it) } })
            SocialHandleRow("Facebook", form.facebook, { onFormChange { copy(facebook = it) } }, ImeAction.Done)
        }
    }
}

/** iOS `socialField(_:text:)`: the network's name, then a right-aligned "handle" field. */
@Composable
private fun SocialHandleRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction = ImeAction.Next,
) {
    val colors = DrokpoTheme.colors
    val textStyle = DrokpoTheme.typography.body.copy(color = colors.label, textAlign = TextAlign.End)
    GroupedRow {
        Text(title)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = textStyle,
            singleLine = true,
            keyboardOptions = literalKeyboard(KeyboardType.Text, imeAction),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) {
                        Text(
                            "handle",
                            style = textStyle,
                            color = colors.placeholderText,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun CommunityContactPersonStep(
    form: CommunityOnboardingForm,
    onFormChange: FormChange,
    modifier: Modifier = Modifier,
) {
    GroupedForm(modifier) {
        GroupedSection(
            header = "Person to contact",
            footer = "Who should members or Drokpo reach out to with questions? Name is required.",
        ) {
            GroupedTextField(
                value = form.contactName,
                onValueChange = { onFormChange { copy(contactName = it) } },
                placeholder = "Name",
                keyboardOptions = textKeyboard(),
            )
            GroupedTextField(
                value = form.contactRole,
                onValueChange = { onFormChange { copy(contactRole = it) } },
                placeholder = "Role (e.g. Coordinator)",
                keyboardOptions = textKeyboard(),
            )
            GroupedTextField(
                value = form.contactPhone,
                onValueChange = { onFormChange { copy(contactPhone = it) } },
                placeholder = "Phone",
                modifier = Modifier.autofill(ContentType.PhoneNumber),
                keyboardOptions = phoneKeyboard(),
            )
            GroupedTextField(
                value = form.contactEmail,
                onValueChange = { onFormChange { copy(contactEmail = it) } },
                placeholder = "Email",
                modifier = Modifier.autofill(ContentType.EmailAddress),
                keyboardOptions = literalKeyboard(KeyboardType.Email, ImeAction.Done),
            )
        }
    }
}

@Composable
internal fun CommunityAddressStep(
    form: CommunityOnboardingForm,
    onFormChange: FormChange,
    modifier: Modifier = Modifier,
) {
    GroupedForm(modifier) {
        GroupedSection(header = "Address", footer = "City and country are required.") {
            GroupedTextField(
                value = form.line1,
                onValueChange = { onFormChange { copy(line1 = it) } },
                placeholder = "Street address",
                keyboardOptions = textKeyboard(),
            )
            GroupedTextField(
                value = form.city,
                onValueChange = { onFormChange { copy(city = it) } },
                placeholder = "City",
                keyboardOptions = textKeyboard(),
            )
            GroupedTextField(
                value = form.state,
                onValueChange = { onFormChange { copy(state = it) } },
                placeholder = "State / province",
                keyboardOptions = textKeyboard(),
            )
            GroupedTextField(
                value = form.country,
                onValueChange = { onFormChange { copy(country = it) } },
                placeholder = "Country",
                keyboardOptions = textKeyboard(),
            )
            GroupedTextField(
                value = form.postalCode,
                onValueChange = { onFormChange { copy(postalCode = it) } },
                placeholder = "Postal code",
                keyboardOptions = textKeyboard(ImeAction.Done),
            )
        }
    }
}

private val TileShape = RoundedCornerShape(10.dp)
private val TileWidth = 100.dp
private val TileHeight = 133.dp

/**
 * "Add a logo and photos": an adaptive grid of 100×133 tiles (iOS
 * `GridItem(.adaptive(minimum: 100), spacing: 8)`), each with an X to remove
 * it, plus a "+" tile while fewer than six are picked. [editable] = false
 * while Finish is uploading, so the grid can't change under the upload.
 */
@Composable
internal fun CommunityPhotosStep(
    photos: List<PickedPhoto>,
    onAddPhotos: () -> Unit,
    onRemovePhoto: (PickedPhoto) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Add a logo and photos",
            style = typography.title2.bold(),
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        Text(
            "Optional — the first photo becomes your logo. You can add or change these later.",
            style = typography.subheadline,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TileWidth),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(photos, key = { _, photo -> photo.key }) { index, photo ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PhotoTile(
                        photo = photo,
                        contentDescription = if (index == 0) "Logo" else "Photo ${index + 1}",
                        editable = editable,
                        onRemove = { onRemovePhoto(photo) },
                    )
                }
            }
            if (photos.size < MAX_COMMUNITY_PHOTOS) {
                item(key = "add-photo") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AddPhotoTile(enabled = editable, onClick = onAddPhotos)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(
    photo: PickedPhoto,
    contentDescription: String,
    editable: Boolean,
    onRemove: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    Box(
        Modifier
            .size(TileWidth, TileHeight)
            .clip(TileShape)
            .background(colors.fill),
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // iOS `xmark.circle.fill` in white on black @ 60%, 4pt in from the corner.
        // The touch target is the whole 36dp corner, not just the 22dp disc.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(36.dp)
                .clickable(
                    enabled = editable,
                    role = Role.Button,
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = 16.dp),
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.photoScrimStrong),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove photo",
                    tint = colors.onPhoto,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** The PhotosPicker label: a `.quaternary` tile with an accent "+" (button tint). */
@Composable
private fun AddPhotoTile(enabled: Boolean, onClick: () -> Unit) {
    val colors = DrokpoTheme.colors
    Box(
        Modifier
            .size(TileWidth, TileHeight)
            .clip(TileShape)
            .background(colors.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "Add photos",
            tint = if (enabled) colors.accent else colors.tertiaryLabel,
            modifier = Modifier.size(28.dp),
        )
    }
}
