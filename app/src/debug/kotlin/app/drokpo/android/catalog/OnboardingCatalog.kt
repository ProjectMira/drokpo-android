package app.drokpo.android.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.features.onboarding.OnboardingFlowScreen
import app.drokpo.android.features.onboarding.ProfileQuestionFields
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.theme.DrokpoTheme

// Group 2 (onboarding) owns this file. Starter entries render the shell's stubs — replace the flow
// entry with OnboardingContent per step + fixtures (CONTRACT.md §E). ProfileQuestionFields is
// stateless and can stay.
val onboardingCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("onboarding.flow", "Onboarding flow (stub)") { OnboardingFlowScreen() },
    CatalogEntry("onboarding.questions", "ProfileQuestionFields (prefilled)") {
        var answers by remember { mutableStateOf(Fixtures.profile.answers.orEmpty()) }
        Scaffold(
            topBar = { DrokpoTopBar("Prompts") },
            containerColor = DrokpoTheme.colors.groupedBackground,
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(DrokpoTheme.colors.groupedBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            ) {
                ProfileQuestionFields(
                    answers = answers,
                    onAnswerChange = { key, value -> answers = answers + (key to value) },
                )
            }
        }
    },
)
