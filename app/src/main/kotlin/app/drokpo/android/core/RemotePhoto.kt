package app.drokpo.android.core

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.Photo
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import coil3.toUri
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renders a profile/community/news photo. Uses the photo's URL when the
 * backend provides one, otherwise resolves a download URL from its Firebase
 * Storage path. Port of iOS `RemotePhotoView`.
 *
 * Fills its bounds with `ContentScale.Crop` (iOS `scaledToFill`) unless told
 * otherwise; size and clip it from outside (`Modifier.size(56.dp).clip(CircleShape)`), or use
 * [PhotoBand] when the photo must not influence the surrounding layout.
 *
 * States (same visuals as iOS):
 * - no photo → grey fill with a person glyph
 * - loading → grey fill with a spinner
 * - failed → grey fill with a broken-image glyph; tap to retry
 *
 * iOS loads the bytes by hand instead of `AsyncImage` because AsyncImage
 * reported a *cancelled* load — routine when cells re-layout inside a
 * List/ScrollView — as a failure and never retried, leaving valid photos
 * stuck on an error icon. Coil treats cancellation as "not finished" and
 * restarts the request when the item re-enters composition, so the same bug
 * can't happen here; the retry/caching policy iOS hand-rolled lives in
 * [RemotePhotoFetcher].
 */
@Composable
fun RemotePhotoView(
    photo: Photo?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    RemotePhotoView(
        storagePath = photo?.storagePath,
        url = photo?.url,
        modifier = modifier,
        contentScale = contentScale,
        contentDescription = contentDescription,
    )
}

/**
 * [RemotePhotoView] for callers holding the raw fields (e.g. a chat photo
 * message or a news card image that isn't wrapped in a [Photo]). A null
 * [storagePath] *and* null [url] renders the "no photo" person placeholder.
 */
@Composable
fun RemotePhotoView(
    storagePath: String?,
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
) {
    val path = storagePath?.takeIf { it.isNotBlank() }
    val link = url?.takeIf { it.isNotBlank() }
    // Cache identity. iOS keys its NSCache by storagePath alone, but that cache is memory-only
    // and empties on relaunch; Coil's disk cache persists. Several "storage paths" are synthetic
    // keys that stay fixed while the image behind them changes (`ad-image-{adId}`,
    // `post-image-{postId}`, `news-image-{newsId}`, ChatImageViewer's `chat-image-viewer`), so
    // the URL is part of the key whenever there is one. Backend URLs are stable token download
    // URLs (storage.ensure_download_url), so real uploads still hit the cache every time.
    val cacheKey = if (path != null && link != null) "$path|$link" else path ?: link
    if (cacheKey == null) {
        PhotoPlaceholder(icon = Icons.Filled.Person, modifier = modifier)
        return
    }

    val context = LocalContext.current
    // Bumping this recreates the AsyncImage → a fresh request (tap-to-retry).
    var attempt by remember(cacheKey) { mutableIntStateOf(0) }
    var phase by remember(cacheKey) { mutableStateOf<PhotoPhase>(PhotoPhase.Loading) }

    Box(modifier.clipToBounds()) {
        // Grey ground the photo crossfades in over (also the placeholder). A
        // non-cropping scale (e.g. Fit in a full-screen viewer) drops it once
        // loaded so the letterbox shows the caller's background.
        if (phase != PhotoPhase.Loaded || contentScale == ContentScale.Crop) {
            Box(Modifier.matchParentSize().background(DrokpoTheme.colors.fill))
        }

        key(cacheKey, attempt) {
            val request = remember(cacheKey, url) {
                ImageRequest.Builder(context)
                    .data(RemotePhotoKey(storagePath = storagePath, url = url))
                    // Registered per request too, so a photo renders even if
                    // the app's singleton loader wasn't built by newDrokpoImageLoader.
                    .fetcherFactory(RemotePhotoFetcher.Factory)
                    // Explicit keys (see cacheKey above): a URL-less photo is cached
                    // under its storage path, so a cached photo needs no
                    // download-URL round trip.
                    .memoryCacheKey("drokpo-photo:$cacheKey")
                    .diskCacheKey("drokpo-photo:$cacheKey")
                    .crossfade(true)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
                onState = { state ->
                    phase = when (state) {
                        is AsyncImagePainter.State.Success -> PhotoPhase.Loaded
                        is AsyncImagePainter.State.Error -> PhotoPhase.Failed
                        else -> PhotoPhase.Loading
                    }
                },
            )
        }

        when (phase) {
            PhotoPhase.Loaded -> Unit
            PhotoPhase.Loading -> PhotoPlaceholderContent(icon = null)
            PhotoPhase.Failed -> PhotoPlaceholderContent(
                icon = Icons.Outlined.BrokenImage,
                modifier = Modifier.clickable { attempt++ },
            )
        }
    }
}

/**
 * A fixed-aspect image band whose photo can never affect surrounding layout.
 *
 * RemotePhotoView renders its image cropped-to-fill, which on iOS overflows
 * its proposed bounds; unclipped, that overflow inflated the layout around it
 * (it once pushed the whole news detail sheet wider than the screen). This
 * container owns the geometry — the band is always [aspect] (width / height)
 * at the offered width — and clips the photo to it.
 */
@Composable
fun PhotoBand(
    photo: Photo?,
    modifier: Modifier = Modifier,
    aspect: Float = 16f / 9f,
) {
    Box(modifier.aspectRatio(aspect).clipToBounds()) {
        RemotePhotoView(photo = photo, modifier = Modifier.fillMaxSize())
    }
}

/** [PhotoBand] for raw storage-path / URL fields. */
@Composable
fun PhotoBand(
    storagePath: String?,
    url: String?,
    modifier: Modifier = Modifier,
    aspect: Float = 16f / 9f,
) {
    Box(modifier.aspectRatio(aspect).clipToBounds()) {
        RemotePhotoView(storagePath = storagePath, url = url, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Coil singleton loader for the app: OkHttp networking, memory + disk cache,
 * crossfade, and the Firebase-Storage-aware [RemotePhotoFetcher]. Installed
 * from DrokpoApplication via `SingletonImageLoader.Factory`:
 *
 * ```
 * class DrokpoApplication : Application(), SingletonImageLoader.Factory {
 *     override fun newImageLoader(context: PlatformContext) = newDrokpoImageLoader(context)
 * }
 * ```
 */
fun newDrokpoImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() }))
            add(RemotePhotoFetcher.Factory)
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

// region Internals

private enum class PhotoPhase { Loading, Loaded, Failed }

/** Coil model for a Drokpo photo (see [RemotePhotoFetcher]). */
internal data class RemotePhotoKey(val storagePath: String?, val url: String?)

/**
 * Resolves a [RemotePhotoKey] to bytes: the backend-provided URL when there is
 * one, otherwise a Firebase Storage download URL for the storage path, then
 * delegates to the loader's network fetcher.
 *
 * Two attempts smooth over transient network blips (same as iOS).
 * Cancellation (the view scrolled away) is not a failure — it propagates, and
 * Coil restarts the request fresh when the view comes back.
 */
internal class RemotePhotoFetcher(
    private val data: RemotePhotoKey,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val url = resolveUrl()
                val (fetcher, _) = imageLoader.components.newFetcher(url.toUri(), options, imageLoader)
                    ?: throw IllegalStateException("No fetcher for $url")
                return fetcher.fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                // A cached download URL may have been revoked; re-resolve next time.
                data.storagePath?.let { downloadUrls.remove(it) }
                if (attempt == 0) delay(1_000)
            }
        }
        throw lastError ?: IllegalStateException("Photo load failed")
    }

    private suspend fun resolveUrl(): String {
        data.url?.takeIf { it.isNotBlank() }?.let { return it }
        val path = data.storagePath ?: throw IllegalStateException("Photo has no URL or storage path")
        downloadUrls[path]?.let { return it }
        // Throws IllegalStateException when Firebase isn't configured (the
        // "Firebase not configured" build) — surfaces as the failed state.
        val resolved = FirebaseStorage.getInstance().reference.child(path).downloadUrl.await().toString()
        downloadUrls[path] = resolved
        return resolved
    }

    object Factory : Fetcher.Factory<RemotePhotoKey> {
        override fun create(data: RemotePhotoKey, options: Options, imageLoader: ImageLoader): Fetcher =
            RemotePhotoFetcher(data, options, imageLoader)
    }

    private companion object {
        /** storagePath → resolved download URL, so each path is resolved once per process. */
        val downloadUrls = ConcurrentHashMap<String, String>()
    }
}

@Composable
private fun PhotoPlaceholder(icon: ImageVector?, modifier: Modifier = Modifier) {
    Box(modifier.background(DrokpoTheme.colors.fill)) {
        PhotoPlaceholderContent(icon = icon)
    }
}

/** Centered glyph (iOS `.font(.largeTitle)` ≈ 34dp, `.secondary`) or spinner. */
@Composable
private fun PhotoPlaceholderContent(icon: ImageVector?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DrokpoTheme.colors.secondaryLabel,
                modifier = Modifier.size(34.dp),
            )
        } else {
            CircularProgressIndicator(
                color = DrokpoTheme.colors.secondaryLabel,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// endregion

@DrokpoPreviews
@androidx.compose.runtime.Composable
private fun RemotePhotoPreview() {
    DrokpoTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // No photo → person placeholder.
                RemotePhotoView(photo = null, modifier = Modifier.size(90.dp, 120.dp).clip(RoundedCornerShape(10.dp)))
                // Loading (previews never reach the network).
                RemotePhotoView(
                    photo = Photo(storagePath = "users/preview/photos/1.jpg"),
                    modifier = Modifier.size(90.dp, 120.dp).clip(RoundedCornerShape(10.dp)),
                )
                PhotoPlaceholder(
                    icon = Icons.Outlined.BrokenImage,
                    modifier = Modifier.size(56.dp).clip(CircleShape),
                )
            }
            PhotoBand(photo = null, modifier = Modifier.width(280.dp))
        }
    }
}
