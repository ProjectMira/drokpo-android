package app.drokpo.android.core.model

// MARK: - Profile questions

/**
 * A profile prompt shown during onboarding/editing and rendered on the
 * profile detail card. `key` is the stable id stored in the profile's
 * `answers` map — never change a key once shipped.
 */
data class ProfileQuestion(
    val key: String,
    val label: String,
    val kind: Kind,
) {
    sealed interface Kind {
        data class Choice(val options: List<String>) : Kind
        data class Text(val placeholder: String) : Kind
    }

    val id: String get() = key
}

// MARK: - Shared vocabulary

object Vocabulary {
    val genders: List<String> = listOf("male", "female")
    val regions: List<String> = listOf(
        "India", "Nepal", "Bhutan",
        "North America", "Europe", "Australia", "Other",
    )
    val languages: List<String> = listOf("Tibetan", "English", "Hindi", "Nepali", "Mandarin", "French", "German", "Other")
    val interests: List<String> = listOf(
        "Momo cooking", "Gorshey", "Hiking", "Music", "Photography",
        "Reading", "Meditation", "Thangka painting", "Basketball", "Soccer",
        "Movies", "Travel", "Board games", "Volunteering",
        "Cooking", "Dancing", "Singing", "Art & design", "Gaming",
        "Cricket", "Chess", "Fitness", "Buddhism & philosophy", "Language exchange",
    )
    val educationLevels: List<String> = listOf(
        "High school", "Some college", "Bachelor's", "Master's", "PhD",
        "Monastic education", "Other",
    )

    /**
     * Friendship-flavoured prompts; all optional. Answers live in the
     * profile's `answers` map keyed by `ProfileQuestion.key`.
     */
    val questions: List<ProfileQuestion> = listOf(
        ProfileQuestion(
            key = "lookingFor",
            label = "I'm here for",
            kind = ProfileQuestion.Kind.Choice(
                listOf("New friends", "Dating", "Friends first, then who knows", "Community & events"),
            ),
        ),
        ProfileQuestion(
            key = "teaChoice",
            label = "Chai or butter tea?",
            kind = ProfileQuestion.Kind.Choice(
                listOf("Chai", "Butter tea", "Both, please", "Coffee person"),
            ),
        ),
        ProfileQuestion(
            key = "travelledTo",
            label = "Places I've travelled to",
            kind = ProfileQuestion.Kind.Text(placeholder = "Dharamshala, Kathmandu, New York…"),
        ),
        ProfileQuestion(
            key = "favoriteMovies",
            label = "Movies I can rewatch forever",
            kind = ProfileQuestion.Kind.Text(placeholder = "Your comfort films"),
        ),
        ProfileQuestion(
            key = "favoriteMusic",
            label = "Songs on repeat",
            kind = ProfileQuestion.Kind.Text(placeholder = "Artists or songs you love right now"),
        ),
        ProfileQuestion(
            key = "perfectWeekend",
            label = "My perfect weekend",
            kind = ProfileQuestion.Kind.Text(placeholder = "Hiking? Momo party? Netflix?"),
        ),
    )

    val reportReasons: List<String> =
        listOf("Fake profile", "Inappropriate photos", "Harassment", "Spam", "Underage", "Other")

    /** Rough fallback coordinates per region for users who decline location access. */
    val regionCoordinates: Map<String, GeoLocation> = linkedMapOf(
        "India" to GeoLocation(lat = 32.22, lng = 76.32),
        "Nepal" to GeoLocation(lat = 27.72, lng = 85.32),
        "Bhutan" to GeoLocation(lat = 27.47, lng = 89.64),
        "North America" to GeoLocation(lat = 40.71, lng = -74.0),
        "Europe" to GeoLocation(lat = 47.37, lng = 8.54),
        "Australia" to GeoLocation(lat = -33.87, lng = 151.21),
        "Other" to GeoLocation(lat = 0.0, lng = 0.0),
    )
}
