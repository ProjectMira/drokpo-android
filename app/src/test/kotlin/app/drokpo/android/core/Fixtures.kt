package app.drokpo.android.core

/**
 * Response bodies shaped like the FastAPI backend actually returns them
 * (drokpo-backend/backend/app: routers + services). Raw Firestore docs carry
 * extra keys (fcmTokens, status, location, updatedAt, …) and timestamps are
 * `datetime.isoformat()` strings.
 */
object Fixtures {
    const val PHOTO = """{"storagePath":"users/u1/photos/a.jpg","order":0,"url":"https://firebasestorage.googleapis.com/v0/b/drokpo/o/a.jpg?alt=media"}"""

    /** users_service.public_summary + distanceKm, as served in the feed. */
    const val PERSON_CARD = """{
        "uid":"u2","displayName":"Pema","dob":"1999-02-03","gender":"female","bio":"Thangka painter",
        "occupation":"Artist","education":"Master's","region":"Nepal","languages":["Tibetan","Nepali"],
        "interests":["Thangka painting","Hiking"],"answers":{"teaChoice":"Both, please"},
        "socials":{"instagram":"pema.art"},"photos":[$PHOTO],"distanceKm":12.3
    }"""

    /** counterparts._community_as_counterpart (+ kind). */
    const val COMMUNITY_COUNTERPART = """{
        "uid":"c1","kind":"community","displayName":"Tibetan Association of Toronto",
        "bio":"Community events","region":"Toronto, Canada","photos":[],"socials":null,
        "verification":"verified","memberCount":12
    }"""

    const val AD = """{
        "adId":"ad1","title":"Learn Tibetan","body":"Classes online","linkUrl":"https://example.org/learn",
        "ctaLabel":"Sign up","photos":[{"storagePath":"ads/ad1/1.jpg","url":"https://example.org/ad.jpg"}]
    }"""

    const val AD_IMAGE_ONLY = """{"adId":"ad2","title":"Momo night","linkUrl":"https://example.org/momo","imageUrl":"https://example.org/momo.jpg"}"""

    const val NEWS = """{
        "newsId":"n1","title":"Losar celebrations","gist":"Short gist","summary":"Long summary",
        "sourceUrl":"https://phayul.com/a","sourceName":"Phayul","imageUrl":"https://phayul.com/a.jpg",
        "publishedAt":"2026-10-05T08:00:00+05:30"
    }"""

    const val POLL_POST = """{
        "postId":"p1","communityId":"c1","communityName":"TAT","communityLogoUrl":"https://example.org/logo.jpg",
        "kind":"poll","title":"Picnic date?","body":"","imageUrl":null,"linkUrl":null,"ctaLabel":null,
        "poll":{"options":[{"id":"opt1","label":"Saturday"},{"id":"opt2","label":"Sunday"}],"counts":{"opt1":3,"opt2":1}},
        "eventAt":null,"location":null,"attendeeCount":null,"active":true,"commentCount":4,
        "createdAt":"2026-10-01T10:00:00.123456+00:00","myVote":"opt1"
    }"""

    const val EVENT_POST = """{
        "postId":"p2","communityId":"c1","communityName":"TAT","kind":"event","title":"Losar party","body":"Join us",
        "poll":null,"eventAt":"2026-12-15T18:00:00+05:30","location":"Community hall","attendeeCount":5,
        "active":true,"commentCount":0,"createdAt":"2026-10-02T10:00:00+00:00","myRsvp":true
    }"""

    const val LINK_POST = """{
        "postId":"p3","communityId":"c1","kind":"link","title":"Read this","linkUrl":"https://example.org/article",
        "ctaLabel":"Read","active":false,"commentCount":1,"createdAt":"2026-10-03T10:00:00+00:00"
    }"""

    const val ANNOUNCEMENT_POST = """{
        "postId":"p4","communityId":"c1","kind":"announcement","title":"Welcome","body":"Hello all",
        "imageUrl":"https://example.org/welcome.jpg","active":true,"commentCount":0,"createdAt":"2026-10-04T10:00:00+00:00"
    }"""

    const val ACCOUNT_PERSON = """{
        "accountType":"person",
        "profile":{
            "uid":"u1","displayName":"Tenzin","dob":"1998-04-12","gender":"male","bio":"Momo enthusiast",
            "occupation":"Engineer","education":"Bachelor's","region":"India","languages":["Tibetan","English"],
            "interests":["Hiking","Momo cooking"],"answers":{"teaChoice":"Butter tea","lookingFor":"New friends"},
            "socials":{"instagram":"tenzin","youtube":"tenzin-yt"},
            "location":{"lat":32.22,"lng":76.32,"geohash":"ttnfv"},
            "preferences":{"ageMin":21,"ageMax":35,"distanceKm":100},
            "photos":[$PHOTO],"fcmTokens":["fcm-token"],"status":"active","onboardingComplete":true,
            "discoverable":false,"createdAt":"2026-07-15T10:20:30.123456+00:00","updatedAt":"2026-07-16T10:20:30+00:00"
        },
        "community":null
    }"""

    const val ACCOUNT_COMMUNITY = """{
        "accountType":"community","profile":null,
        "community":{
            "uid":"c1","name":"Tibetan Association of Toronto","description":"Community events",
            "website":"https://tat.example.org","phone":"+1 416 555 0100","email":"hello@tat.example.org",
            "contactPerson":{"name":"Dolma","role":"Secretary"},
            "address":{"line1":"1 Main St","city":"Toronto","country":"Canada","postalCode":"M5V 1A1"},
            "socials":{"facebook":"tat"},"photos":[],"verification":"pending","memberCount":0,
            "fcmTokens":[],"createdAt":"2026-07-15T10:20:30.123456+00:00","updatedAt":"2026-07-15T10:20:30.123456+00:00"
        }
    }"""

    const val ACCOUNT_NONE = """{"accountType":"none","profile":null,"community":null}"""

    /** GET /api/feed?shape=items — discover.build_items plus forward-compat junk. */
    const val FEED_ITEMS = """{"items":[
        {"type":"person","data":$PERSON_CARD},
        {"type":"person","data":$COMMUNITY_COUNTERPART},
        {"type":"ad","data":$AD},
        {"type":"news","data":$NEWS},
        {"type":"communityPost","data":$POLL_POST},
        {"type":"quiz","data":{"quizId":"q1"}},
        {"type":"person","data":{"displayName":"no uid"}},
        {"type":"news"},
        {"type":"ad","data":null},
        {"type":7,"data":{}},
        {"data":$AD_IMAGE_ONLY},
        null,
        42,
        "person",
        {"type":"communityPost","data":$EVENT_POST}
    ]}"""

    /** GET /api/feed (legacy shape). */
    const val FEED_LEGACY = """{
        "candidates":[$PERSON_CARD],"ads":[$AD,$AD_IMAGE_ONLY],"news":[$NEWS],"communityPosts":[$POLL_POST,$EVENT_POST]
    }"""

    const val DIRECTORY_COMMUNITY = """{
        "uid":"c1","name":"Tibetan Association of Toronto","description":"Community events",
        "website":"https://tat.example.org","socials":{"instagram":"tat"},"photos":[$PHOTO],
        "verification":"verified","memberCount":12,"joined":true
    }"""

    const val COMMUNITIES_HOME = """{
        "communities":[$DIRECTORY_COMMUNITY],
        "items":[
            {"type":"communityPost","data":$ANNOUNCEMENT_POST},
            {"type":"communityPost","data":$LINK_POST},
            {"type":"carousel","data":{}},
            {"type":"communityPost","data":{"title":"missing postId"}},
            {"type":"ad","data":$AD}
        ]
    }"""

    /** GET /api/likes/content */
    const val LIKED_CONTENT = """{"items":[
        {"type":"news","likedAt":"2026-10-05T09:00:00.654321+00:00","data":{"newsId":"n1","title":"Losar","gist":"g","sourceUrl":"https://phayul.com/a","likedAt":"2026-10-05T09:00:00.654321+00:00"}},
        {"type":"communityPost","likedAt":"2026-10-04T09:00:00+00:00","data":$POLL_POST},
        {"type":"person","likedAt":"2026-10-03T09:00:00+00:00","data":$PERSON_CARD},
        {"type":"news","likedAt":12345,"data":{"newsId":"n9"}},
        {"type":"news","likedAt":null,"data":{"newsId":"n10"}},
        {"type":"communityPost","data":{"title":"no id"}}
    ]}"""

    const val MATCHES = """{"matches":[
        {"matchId":"u1_u2","users":["u1","u2"],"status":"active","createdAt":"2026-10-01T10:00:00.5+00:00",
         "lastMessage":{"text":"Tashi delek!","senderId":"u2","createdAt":"2026-10-02T11:00:00+00:00"},
         "unreadCount":{"u1":2,"u2":0},"otherUser":{"uid":"u2","kind":"person","displayName":"Pema","photos":[$PHOTO]}},
        {"matchId":"c1_u1","users":["c1","u1"],"status":"active","createdAt":"2026-10-03T10:00:00+00:00",
         "lastMessage":null,"unreadCount":{"u1":0,"c1":0},"otherUser":$COMMUNITY_COUNTERPART}
    ]}"""

    const val SWIPES = """{"swipes":[
        {"uid":"u2","action":"like","fromUid":"u1","toUid":"u2","createdAt":"2026-10-01T10:00:00.123+00:00",
         "otherUser":$PERSON_CARD,"matchId":null,"matchStatus":null},
        {"uid":"c1","action":"superlike","fromUid":"c1","toUid":"u1","createdAt":"2026-10-02T10:00:00+00:00",
         "otherUser":$COMMUNITY_COUNTERPART,"matchId":"c1_u1","matchStatus":"active"},
        {"uid":"u3","action":"like","createdAt":"2026-10-02T10:00:00+00:00","otherUser":{"uid":"u3"},"matchId":null,"matchStatus":"unmatched"}
    ]}"""

    const val SENT_MESSAGES = """{"messages":[
        {"messageId":"m1","matchId":"u1_u2","senderId":"u1","text":"Hello","imageUrl":null,"audioUrl":null,
         "audioDurationSec":null,"createdAt":"2026-10-06T09:00:00.5+00:00","readAt":null},
        {"messageId":"m2","matchId":"u1_u2","senderId":"u1","text":"📷 Photo","imageUrl":"https://example.org/p.jpg",
         "createdAt":"2026-10-06T09:05:00+00:00"}
    ]}"""

    const val POSTS = """{"posts":[$ANNOUNCEMENT_POST,$LINK_POST,$POLL_POST,$EVENT_POST]}"""

    const val DIRECTORY = """{"communities":[$DIRECTORY_COMMUNITY,{"uid":"c2","name":"Dharamsala Youth","photos":[],"memberCount":3,"joined":false}]}"""

    const val MEMBERS = """{"members":[
        {"uid":"u1","displayName":"Tenzin","photo":$PHOTO,"region":"India"},
        {"uid":"u2","displayName":null,"photo":null,"region":null}
    ]}"""

    const val COMMENT = """{
        "commentId":"cm1","authorUid":"u1","authorKind":"person","authorName":"Tenzin",
        "authorPhotoUrl":"https://example.org/t.jpg","text":"Count me in!","audioUrl":null,"audioDurationSec":null,
        "parentId":null,"replyCount":2,"likeCount":3,"dislikeCount":0,"createdAt":"2026-10-05T10:00:00.123456+00:00","myVote":"like"
    }"""

    const val COMMENTS = """{"comments":[
        $COMMENT,
        {"commentId":"cm2","authorUid":"c1","authorKind":"community","authorName":"TAT","authorPhotoUrl":null,
         "text":null,"audioUrl":"https://example.org/voice.m4a","audioDurationSec":12,"parentId":null,
         "replyCount":0,"likeCount":0,"dislikeCount":1,"createdAt":"2026-10-05T11:00:00+00:00","myVote":"dislike"},
        {"commentId":"cm3","authorUid":null,"authorKind":null,"authorName":"Deleted account","authorPhotoUrl":null,
         "text":"This comment was deleted.","audioUrl":null,"audioDurationSec":null,"parentId":null,"replyCount":1,
         "likeCount":0,"dislikeCount":0,"createdAt":"2026-10-05T12:00:00+00:00","deleted":true,"myVote":null}
    ]}"""

    const val REPLIES = """{"replies":[
        {"commentId":"r1","authorUid":"u2","authorKind":"person","authorName":"Pema","text":"Me too",
         "parentId":"cm1","replyCount":0,"likeCount":0,"dislikeCount":0,"createdAt":"2026-10-05T10:05:00+00:00","myVote":null}
    ]}"""

    const val COMMENT_VOTE = """{"likeCount":4,"dislikeCount":0,"myVote":"like"}"""
    const val COMMENT_VOTE_CLEARED = """{"likeCount":3,"dislikeCount":0,"myVote":null}"""

    const val VOTE_RESULT = """{"poll":{"options":[{"id":"opt1","label":"Saturday"},{"id":"opt2","label":"Sunday"}],"counts":{"opt1":4,"opt2":1}},"myVote":"opt1"}"""

    const val RSVP_RESULT = """{"attendeeCount":6,"going":true}"""

    const val SWIPE_MATCHED = """{"matched":true,"matchId":"u1_u2"}"""
    const val SWIPE_UNMATCHED = """{"matched":false,"matchId":null}"""
}
