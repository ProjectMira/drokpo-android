package app.drokpo.android.features.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubPlaceholder

/**
 * Port of Shared/ProfileQuestionFields: one GroupedSection(header = question.label) per
 * Vocabulary.questions entry, stacked in a Column (NOT lazy — place inside a scrolling Column or a
 * single LazyColumn item). choice → dropdown with "Skip" ("" value) + options, label hidden;
 * text → 1–4 line field with the question's placeholder. Empty answers are stripped by the caller.
 * (CONTRACT §B.2 — stub.)
 */
@Composable
fun ProfileQuestionFields(
    answers: Map<String, String>,
    onAnswerChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    StubPlaceholder("ProfileQuestionFields (${answers.size} answered)", modifier.fillMaxWidth())
}
