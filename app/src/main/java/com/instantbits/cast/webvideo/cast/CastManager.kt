package com.instantbits.cast.webvideo.cast

import android.app.Activity
import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.media.MediaNotificationManager
import com.google.android.gms.common.images.WebImage
import com.instantbits.cast.webvideo.R
import com.instantbits.cast.webvideo.video.VideoExtractionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Manages Google Cast SDK integration for casting videos to TV.
 * Uses Default Receiver (CC1AD845) - no custom receiver needed.
 */
class CastManager(
    private val activity: Activity,
    private val onCastStateChanged: (CastState) -> Unit
) : SessionManagerListener<CastSession> {

    private val castContext: CastContext = CastContext.getSharedInstance(activity)
    private var castSession: CastSession? = null
    private var remoteMediaClient: RemoteMediaClient? = null
    private var mediaRouter: MediaRouter? = null
    private var mediaRouteButton: MediaRouteButton? = null

    private var currentVideoUrl: String? = null
    private var currentVideoTitle: String? = null
    private var currentVideoMimeType: String? = null

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Initialize Cast SDK and MediaRouter.
     * Call from Activity.onCreate()
     */
    fun initialize(mediaRouteButton: MediaRouteButton) {
        this.mediaRouteButton = mediaRouteButton

        // Set up MediaRouter for device discovery
        mediaRouter = MediaRouter.getInstance(activity)
        mediaRouteButton.setRouteSelector(
            castContext.getCastOptions(activity).routeSelector
        )

        // Add session manager listener
        castContext.sessionManager.addSessionManagerListener(this, CastSession::class.java)

        // Get current session if already connected
        castSession = castContext.sessionManager.currentCastSession
        if (castSession != null) {
            onSessionStarted(castSession!!, null)
        }

        Timber.d("CastManager initialized")
    }

    /**
     * Clean up resources. Call from Activity.onDestroy()
     */
    fun cleanup() {
        castContext.sessionManager.removeSessionManagerListener(this)
        scope.cancel()
        Timber.d("CastManager cleaned up")
    }

    /**
     * Cast a video to the connected device.
     */
    fun castVideo(result: VideoExtractionResult) {
        val session = castSession ?: return
        val client = remoteMediaClient ?: return

        if (!result.hasVideo) {
            Timber.w("No video URL to cast")
            return
        }

        currentVideoUrl = result.videoUrl
        currentVideoTitle = result.pageTitle
        currentVideoMimeType = result.mimeType

        scope.launch {
            try {
                val mediaInfo = buildMediaInfo(result)
                val loadRequest = com.google.android.gms.cast.framework.media.LoadRequest(mediaInfo)
                    .setAutoplay(true)
                    .setCurrentTime(0)

                val task = client.load(loadRequest)
                val status = kotlinx.coroutines.tasks.await(task)

                if (status != null) {
                    Timber.d("Video cast successfully: ${result.videoUrl}")
                    onCastStateChanged(CastState.Playing)
                } else {
                    Timber.e("Failed to cast video: null status")
                    onCastStateChanged(CastState.Error("Cast failed"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error casting video")
                onCastStateChanged(CastState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Stop casting and disconnect.
     */
    fun stopCasting() {
        remoteMediaClient?.stop()
        castContext.sessionManager.endCurrentSession(true)
        currentVideoUrl = null
        currentVideoTitle = null
        currentVideoMimeType = null
        onCastStateChanged(CastState.Disconnected)
    }

    /**
     * Pause playback on TV.
     */
    fun pause() {
        remoteMediaClient?.pause()
    }

    /**
     * Resume playback on TV.
     */
    fun play() {
        remoteMediaClient?.play()
    }

    /**
     * Seek to position in milliseconds.
     */
    fun seek(position: Long) {
        remoteMediaClient?.seek(position)
    }

    /**
     * Set volume (0.0 to 1.0).
     */
    fun setVolume(volume: Float) {
        remoteMediaClient?.setStreamVolume(volume.coerceIn(0f, 1f))
    }

    /**
     * Get current cast state.
     */
    fun getCastState(): CastState {
        return if (castSession == null) {
            CastState.Disconnected
        } else if (remoteMediaClient == null) {
            CastState.Connecting
        } else {
            val status = remoteMediaClient!!.mediaStatus
            if (status == null) return CastState.Connected

            when (status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> CastState.Playing
                MediaStatus.PLAYER_STATE_PAUSED -> CastState.Paused
                MediaStatus.PLAYER_STATE_BUFFERING -> CastState.Buffering
                MediaStatus.PLAYER_STATE_IDLE -> CastState.Idle
                else -> CastState.Connected
            }
        }
    }

    /**
     * Check if currently casting.
     */
    fun isCasting(): Boolean {
        return castSession != null && remoteMediaClient != null
    }

    /**
     * Get the name of the connected device.
     */
    fun getDeviceName(): String? {
        return castSession?.device?.friendlyName
    }

    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentPosition(): Long {
        return remoteMediaClient?.getApproximateStreamPosition() ?: 0
    }

    /**
     * Get media duration in milliseconds.
     */
    fun getDuration(): Long {
        return remoteMediaClient?.mediaStatus?.mediaInfo?.streamDuration ?: 0
    }

    // SessionManagerListener callbacks

    override fun onSessionStarting(session: CastSession) {
        Timber.d("Cast session starting: ${session.device.friendlyName}")
        onCastStateChanged(CastState.Connecting)
    }

    override fun onSessionStarted(session: CastSession, sessionId: String?) {
        Timber.d("Cast session started: ${session.device.friendlyName}")
        castSession = session
        remoteMediaClient = session.remoteMediaClient
        remoteMediaClient?.addProgressListener({ _, _ -> }, 1000)
        onCastStateChanged(CastState.Connected)
    }

    override fun onSessionStartFailed(session: CastSession, error: Int) {
        Timber.e("Cast session start failed: $error")
        castSession = null
        remoteMediaClient = null
        onCastStateChanged(CastState.Error("Connection failed: $error"))
    }

    override fun onSessionEnding(session: CastSession) {
        Timber.d("Cast session ending")
    }

    override fun onSessionEnded(session: CastSession, error: Int) {
        Timber.d("Cast session ended: $error")
        castSession = null
        remoteMediaClient = null
        currentVideoUrl = null
        currentVideoTitle = null
        currentVideoMimeType = null
        onCastStateChanged(CastState.Disconnected)
    }

    override fun onSessionResuming(session: CastSession, sessionId: String) {
        Timber.d("Cast session resuming")
    }

    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
        Timber.d("Cast session resumed")
        castSession = session
        remoteMediaClient = session.remoteMediaClient
        onCastStateChanged(CastState.Connected)
    }

    override fun onSessionResumeFailed(session: CastSession, error: Int) {
        Timber.e("Cast session resume failed: $error")
    }

    override fun onSessionSuspended(session: CastSession, reason: Int) {
        Timber.d("Cast session suspended: $reason")
    }

    /**
     * Build MediaInfo from video extraction result.
     */
    private fun buildMediaInfo(result: VideoExtractionResult): MediaInfo {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        metadata.putString(MediaMetadata.KEY_TITLE, result.pageTitle)
        metadata.putString(MediaMetadata.KEY_SUBTITLE, "Streaming from ${result.pageUrl}")
        // Add thumbnail if available (could extract from page)
        // metadata.addImage(WebImage(Uri.parse(thumbnailUrl)))

        val streamType = when (result.mimeType) {
            "application/vnd.apple.mpegurl" -> MediaInfo.STREAM_TYPE_BUFFERED // HLS
            "application/dash+xml" -> MediaInfo.STREAM_TYPE_BUFFERED // DASH
            "video/youtube" -> MediaInfo.STREAM_TYPE_BUFFERED
            "video/vimeo" -> MediaInfo.STREAM_TYPE_BUFFERED
            else -> MediaInfo.STREAM_TYPE_BUFFERED
        }

        return MediaInfo.Builder(result.videoUrl!!)
            .setStreamType(streamType)
            .setContentType(result.mimeType ?: "video/mp4")
            .setMetadata(metadata)
            .build()
    }

    companion object {
        /**
         * Show the Cast dialog manually.
         */
        fun showCastDialog(activity: Activity) {
            CastContext.getSharedInstance(activity).presentationDisplayController
                .showCastDialog(activity, null)
        }
    }
}

/**
 * Cast connection states.
 */
sealed class CastState {
    data class Connected(val deviceName: String? = null) : CastState()
    object Connecting : CastState()
    object Disconnected : CastState()
    data class Playing(val position: Long = 0, val duration: Long = 0) : CastState()
    data class Paused(val position: Long = 0, val duration: Long = 0) : CastState()
    data class Buffering(val position: Long = 0) : CastState()
    data class Idle(val position: Long = 0) : CastState()
    data class Error(val message: String) : CastState()

    override fun toString(): String = when (this) {
        is Connected -> "Connected${deviceName?.let { " to $it" } ?: ""}"
        is Connecting -> "Connecting"
        is Disconnected -> "Disconnected"
        is Playing -> "Playing"
        is Paused -> "Paused"
        is Buffering -> "Buffering"
        is Idle -> "Idle"
        is Error -> "Error: $message"
    }
}