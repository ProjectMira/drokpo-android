package app.drokpo.android.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.drokpo.android.core.model.ProfileQuestion
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.ui.components.GroupedDefaults
import app.drokpo.android.ui.components.GroupedPickerRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.GroupedTextField

/**
 * Port of Shared/ProfileQuestionFields (CONTRACT.md §B.2): form sections for
 * the profile prompts (`Vocabulary.questions`), shared by onboarding's
 * About-you step and the profile editor. Reads from an `answers` map keyed by
 * `ProfileQuestion.key` and reports each change through [onAnswerChange];
 * empty answers are stripped by the caller before submitting.
 *
 * One `GroupedSection(header = question.label)` per question, stacked in a
 * Column with grouped section spacing — NOT lazy, so place it inside a
 * scrolling Column (GroupedForm) or a single LazyColumn item.
 * - choice → a menu picker with "Skip" (the "" value) + the options, label
 *   hidden (iOS `.pickerStyle(.menu).labelsHidden()`).
 * - text → a 1–4 line field with the question's placeholder.
 */
@Composable
fun ProfileQuestionFields(
    answers: Map<String, String>,
    onAnswerChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GroupedDefaults.SectionSpacing),
    ) {
        Vocabulary.questions.forEach { question ->
            key(question.key) {
                GroupedSection(header = question.label) {
                    val value = answers[question.key].orEmpty()
                    when (val kind = question.kind) {
                        is ProfileQuestion.Kind.Choice -> GroupedPickerRow(
                            title = null,
                            options = listOf("") + kind.options,
                            selected = value,
                            onSelect = { onAnswerChange(question.key, it) },
                            optionLabel = { it.ifEmpty { "Skip" } },
                        )
                        is ProfileQuestion.Kind.Text -> GroupedTextField(
                            value = value,
                            onValueChange = { onAnswerChange(question.key, it) },
                            placeholder = kind.placeholder,
                            singleLine = false,
                            minLines = 1,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    }
                }
            }
        }
    }
}
