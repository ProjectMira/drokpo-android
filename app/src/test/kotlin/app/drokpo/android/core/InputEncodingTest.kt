package app.drokpo.android.core

import app.drokpo.android.core.model.CommentIn
import app.drokpo.android.core.model.CommentVoteIn
import app.drokpo.android.core.model.CommunityAddress
import app.drokpo.android.core.model.CommunityOnboardingIn
import app.drokpo.android.core.model.CommunityPhotoConfirm
import app.drokpo.android.core.model.CommunityPhotoOrderUpdate
import app.drokpo.android.core.model.CommunityPostIn
import app.drokpo.android.core.model.CommunityPostUpdate
import app.drokpo.android.core.model.CommunityUpdate
import app.drokpo.android.core.model.ContactPerson
import app.drokpo.android.core.model.ContentEventIn
import app.drokpo.android.core.model.FcmTokenIn
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.MessageIn
import app.drokpo.android.core.model.OnboardingIn
import app.drokpo.android.core.model.PhotoConfirm
import app.drokpo.android.core.model.PhotoOrderUpdate
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.core.model.ProfileUpdate
import app.drokpo.android.core.model.ReportIn
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.SwipeAction
import app.drokpo.android.core.model.SwipeIn
import app.drokpo.android.core.model.VoteIn
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Exact wire bodies, compared with what Swift's synthesized Encodable produces
 * for the same values: nil optionals omitted, non-optional defaults present,
 * and "" kept as the backend's "clear this field" signal.
 */
class InputEncodingTest {
    private inline fun <reified T> encode(value: T): String {
        val json = DrokpoJson.encodeToString(value)
        assertFalse("explicit null in $json", json.contains("null"))
        return json
    }

    @Test
    fun communityOnboardingOmitsNils() {
        // CommunityOnboardingModel.createCommunity: optional fields via nonEmpty().
        val body = CommunityOnboardingIn(
            name = "Tibetan Association of Toronto",
            description = "Community events",
            website = null,
            phone = "+1 416",
            email = "hello@tat.org",
            contactPerson = ContactPerson(name = "Dolma", role = null, phone = null, email = "d@tat.org"),
            address = CommunityAddress(line1 = null, city = "Toronto", state = null, country = "Canada", postalCode = null),
            socials = Socials(instagram = "tat", youtube = null, tiktok = null, facebook = null),
        )
        assertEquals(
            """{"name":"Tibetan Association of Toronto","description":"Community events","phone":"+1 416",""" +
                """"email":"hello@tat.org","contactPerson":{"name":"Dolma","email":"d@tat.org"},""" +
                """"address":{"city":"Toronto","country":"Canada"},"socials":{"instagram":"tat"}}""",
            encode(body),
        )
    }

    @Test
    fun communityUpdateKeepsEmptyStringClears() {
        // CommunityProfileEditorView.save: clearable optionals go up as "",
        // never-clearable ones (email, contact name, city, country) as nil.
        val body = CommunityUpdate(
            name = "TAT",
            description = "Events",
            website = "",
            phone = "",
            email = null,
            contactPerson = ContactPerson(name = null, role = "", phone = "", email = ""),
            address = CommunityAddress(line1 = "", city = null, state = "", country = null, postalCode = ""),
            socials = Socials(instagram = "", youtube = "yt", tiktok = "", facebook = ""),
        )
        assertEquals(
            """{"name":"TAT","description":"Events","website":"","phone":"",""" +
                """"contactPerson":{"role":"","phone":"","email":""},""" +
                """"address":{"line1":"","state":"","postalCode":""},""" +
                """"socials":{"instagram":"","youtube":"yt","tiktok":"","facebook":""}}""",
            encode(body),
        )
        assertEquals("{}", encode(CommunityUpdate()))
    }

    @Test
    fun photoBodies() {
        assertEquals("""{"storagePath":"communities/c1/photos/a.jpg","order":2}""", encode(CommunityPhotoConfirm("communities/c1/photos/a.jpg", 2)))
        assertEquals("""{"storagePaths":["b","a"]}""", encode(CommunityPhotoOrderUpdate(listOf("b", "a"))))
        assertEquals("""{"storagePath":"users/u1/photos/a.jpg","order":0}""", encode(PhotoConfirm("users/u1/photos/a.jpg", 0)))
        assertEquals("""{"storagePaths":[]}""", encode(PhotoOrderUpdate(emptyList())))
    }

    @Test
    fun communityPostInAlwaysSendsBody() {
        assertEquals(
            """{"kind":"announcement","title":"Hello","body":""}""",
            encode(CommunityPostIn(kind = "announcement", title = "Hello")),
        )
        assertEquals(
            """{"kind":"poll","title":"Picnic?","body":"","pollOptions":["Sat","Sun"]}""",
            encode(CommunityPostIn(kind = "poll", title = "Picnic?", pollOptions = listOf("Sat", "Sun"))),
        )
        assertEquals(
            """{"kind":"event","title":"Losar","body":"Party","photoStoragePath":"communities/c1/photos/x.jpg",""" +
                """"linkUrl":"https://e.org","ctaLabel":"Tickets","eventAt":"2026-12-15T18:00:00+05:30","location":"Hall"}""",
            encode(
                CommunityPostIn(
                    kind = "event",
                    title = "Losar",
                    body = "Party",
                    photoStoragePath = "communities/c1/photos/x.jpg",
                    linkUrl = "https://e.org",
                    ctaLabel = "Tickets",
                    eventAt = "2026-12-15T18:00:00+05:30",
                    location = "Hall",
                ),
            ),
        )
        assertEquals(
            """{"kind":"link","title":"Read","body":"","imageUrl":"https://i.org/x.jpg","linkUrl":"https://e.org"}""",
            encode(CommunityPostIn(kind = "link", title = "Read", imageUrl = "https://i.org/x.jpg", linkUrl = "https://e.org")),
        )
    }

    @Test
    fun communityPostUpdate() {
        // CommunityPageView.toggleActive
        assertEquals("""{"active":false}""", encode(CommunityPostUpdate(active = false)))
        assertEquals(
            """{"title":"T","body":"","imageUrl":"https://i","linkUrl":"https://l","ctaLabel":"Go","active":true}""",
            encode(CommunityPostUpdate("T", "", "https://i", "https://l", "Go", true)),
        )
    }

    @Test
    fun votesAndComments() {
        assertEquals("""{"optionId":"opt2"}""", encode(VoteIn("opt2")))
        assertEquals("""{"text":"Nice","parentId":"cm1"}""", encode(CommentIn(text = "Nice", parentId = "cm1")))
        assertEquals("""{"text":"Top level"}""", encode(CommentIn(text = "Top level")))
        assertEquals(
            """{"audioStoragePath":"commentAudio/u1/a.m4a","audioDurationSec":12}""",
            encode(CommentIn(audioStoragePath = "commentAudio/u1/a.m4a", audioDurationSec = 12)),
        )
        assertEquals("""{"value":"dislike"}""", encode(CommentVoteIn("dislike")))
    }

    @Test
    fun onboardingIn() {
        val body = OnboardingIn(
            displayName = "Tenzin",
            dob = "1998-04-12",
            gender = null,
            bio = "",
            occupation = "Engineer",
            education = "Bachelor's",
            region = "India",
            languages = listOf("Tibetan"),
            interests = emptyList(),
            answers = mapOf("teaChoice" to "Chai"),
            socials = Socials(instagram = "", youtube = "", tiktok = ""),
            location = GeoLocation(lat = 32.22, lng = 76.32),
            preferences = Preferences(),
        )
        assertEquals(
            """{"displayName":"Tenzin","dob":"1998-04-12","bio":"","occupation":"Engineer","education":"Bachelor's",""" +
                """"region":"India","languages":["Tibetan"],"interests":[],"answers":{"teaChoice":"Chai"},""" +
                """"socials":{"instagram":"","youtube":"","tiktok":""},"location":{"lat":32.22,"lng":76.32},""" +
                """"preferences":{"ageMin":18,"ageMax":99,"distanceKm":50}}""",
            encode(body),
        )
        assertEquals(
            """{"displayName":"P","dob":"2000-01-01","gender":"female","bio":"b","occupation":"","education":"",""" +
                """"region":"Other","languages":[],"interests":[],"answers":{},"socials":{},""" +
                """"location":{"lat":0.0,"lng":0.0},"preferences":{"ageMin":20,"ageMax":30,"distanceKm":10}}""",
            encode(
                OnboardingIn(
                    "P", "2000-01-01", "female", "b", "", "", "Other", emptyList(), emptyList(), emptyMap(),
                    Socials(), GeoLocation(0.0, 0.0), Preferences(20, 30, 10),
                ),
            ),
        )
    }

    @Test
    fun profileUpdate() {
        // ProfileView.setDiscoverable
        assertEquals("""{"discoverable":false}""", encode(ProfileUpdate(discoverable = false)))
        assertEquals("{}", encode(ProfileUpdate()))
        // ProfileView.save: gender nil when empty, emptied socials as "".
        val body = ProfileUpdate(
            displayName = "Tenzin",
            bio = "",
            dob = "1998-04-12",
            gender = null,
            occupation = "",
            education = "PhD",
            region = "Europe",
            languages = listOf("English"),
            interests = listOf("Chess"),
            answers = emptyMap(),
            socials = Socials(instagram = "", youtube = "", tiktok = "tt"),
            location = null,
            preferences = Preferences(ageMin = 25, ageMax = 40, distanceKm = 200),
        )
        assertEquals(
            """{"displayName":"Tenzin","bio":"","dob":"1998-04-12","occupation":"","education":"PhD","region":"Europe",""" +
                """"languages":["English"],"interests":["Chess"],"answers":{},"socials":{"instagram":"","youtube":"","tiktok":"tt"},""" +
                """"preferences":{"ageMin":25,"ageMax":40,"distanceKm":200}}""",
            encode(body),
        )
        assertEquals(
            """{"location":{"lat":-33.87,"lng":151.21}}""",
            encode(ProfileUpdate(location = GeoLocation(-33.87, 151.21))),
        )
    }

    @Test
    fun smallBodies() {
        assertEquals("""{"action":"like"}""", encode(SwipeIn(SwipeAction.like)))
        assertEquals("""{"action":"pass"}""", encode(SwipeIn(SwipeAction.pass)))
        assertEquals("""{"action":"superlike"}""", encode(SwipeIn(SwipeAction.superlike)))
        assertEquals("superlike", SwipeAction.superlike.rawValue)
        assertEquals("""{"text":"hi"}""", encode(MessageIn("hi")))
        assertEquals("""{"token":"fcm"}""", encode(FcmTokenIn("fcm")))
        assertEquals(
            """{"reportedUid":"u2","reason":"Spam","note":""}""",
            encode(ReportIn(reportedUid = "u2", reason = "Spam", note = "")),
        )
        assertEquals("""{"event":"impression"}""", encode(ContentEventIn("impression")))
        assertEquals("""{"event":"click"}""", encode(ContentEventIn("click")))
    }
}
