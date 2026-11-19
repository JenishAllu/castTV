package com.instantbits.cast.webvideo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.instantbits.cast.webvideo.R
import com.instantbits.cast.webvideo.cast.CastManager
import com.instantbits.cast.webvideo.cast.CastState
import com.instantbits.cast.webvideo.video.VideoExtractor
import com.instantbits.cast.webvideo.video.VideoExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Main Activity - WebView browser with Chromecast integration.
 * The phone acts as a remote control; video plays ONLY on the TV.
 */
class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var webView: WebView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var appBarLayout: AppBarLayout
    private lateinit var urlEditText: TextInputEditText
    private lateinit var urlInputLayout: TextInputLayout
    private lateinit var castFab: FloatingActionButton
    private lateinit var videoFoundCard: MaterialCardView
    private lateinit var videoFoundText: TextView
    private lateinit var castNowButton: Button
    private lateinit var castStatusBar: MaterialCardView
    private lateinit var castStatusIcon: android.widget.ImageView
    private lateinit var castStatusText: TextView
    private lateinit var stopCastButton: Button
    private lateinit var progressBar: ProgressBar

    // Core Components
    private lateinit var castManager: CastManager
    private lateinit var videoExtractor: VideoExtractor

    // State
    private var currentUrl: String = "https://www.youtube.com"
    private var lastExtractedVideo: VideoExtractionResult? = null
    private var isExtracting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize components
        videoExtractor = VideoExtractor()
        castManager = CastManager(this) { state ->
            runOnUiThread { updateCastUI(state) }
        }

        initViews()
        setupWebView()
        setupToolbar()
        setupListeners()
        castManager.initialize(findViewById(R.id.castButton))

        // Handle shared intent (from browser share)
        handleIntent(intent)

        // Load home page
        loadUrl(currentUrl)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        val settings = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36"
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }

        // Enable cookies for video sites
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view.loadUrl(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                currentUrl = url ?: currentUrl
                urlEditText.setText(currentUrl)
                videoFoundCard.visibility = View.GONE
                lastExtractedVideo = null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                // Extract video after page loads
                extractVideoFromPage()
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                progressBar.visibility = View.GONE
                Timber.e("WebView error: ${error?.description}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                toolbar.title = title ?: getString(R.string.app_name)
            }
        }

        // Handle edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        toolbar = findViewById(R.id.toolbar)
        appBarLayout = findViewById(R.id.appBarLayout)
        urlEditText = findViewById(R.id.urlEditText)
        urlInputLayout = findViewById(R.id.urlInputLayout)
        castFab = findViewById(R.id.castFab)
        videoFoundCard = findViewById(R.id.videoFoundCard)
        videoFoundText = findViewById(R.id.videoFoundText)
        castNowButton = findViewById(R.id.castNowButton)
        castStatusBar = findViewById(R.id.castStatusBar)
        castStatusIcon = findViewById(R.id.castStatusIcon)
        castStatusText = findViewById(R.id.castStatusText)
        stopCastButton = findViewById(R.id.stopCastButton)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    private fun setupListeners() {
        // URL bar - enter to navigate
        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val url = urlEditText.text.toString().trim()
                if (url.isNotEmpty()) {
                    loadUrl(normalizeUrl(url))
                    urlEditText.clearFocus()
                }
                true
            } else false
        }

        // Refresh button in URL bar
        urlInputLayout.endIconOnClickListener = {
            webView.reload()
        }

        // Cast FAB
        castFab.setOnClickListener {
            if (castManager.isCasting()) {
                showCastControls()
            } else {
                CastManager.showCastDialog(this)
            }
        }

        // Cast Now button (when video found)
        castNowButton.setOnClickListener {
            lastExtractedVideo?.let { castManager.castVideo(it) }
        }

        // Stop casting button
        stopCastButton.setOnClickListener {
            castManager.stopCasting()
        }

        // Toolbar menu
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    webView.reload()
                    true
                }
                R.id.action_home -> {
                    loadUrl("https://www.youtube.com")
                    true
                }
                R.id.action_settings -> {
                    showSettings()
                    true
                }
                R.id.action_about -> {
                    showAbout()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadUrl(url: String) {
        currentUrl = normalizeUrl(url)
        webView.loadUrl(currentUrl)
        urlEditText.setText(currentUrl)
        videoFoundCard.visibility = View.GONE
        lastExtractedVideo = null
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        // If it looks like a domain, add https
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }
        // Otherwise treat as search query
        return "https://www.google.com/search?q=${Uri.encode(trimmed)}"
    }

    private fun extractVideoFromPage() {
        if (isExtracting) return
        isExtracting = true

        lifecycleScope.launch(Dispatchers.IO) {
            val result = videoExtractor.extractVideoUrl(webView)
            withContext(Dispatchers.Main) {
                isExtracting = false
                handleExtractionResult(result)
            }
        }
    }

    private fun handleExtractionResult(result: VideoExtractionResult) {
        lastExtractedVideo = result

        if (result.hasVideo) {
            videoFoundText.text = getString(R.string.video_found)
            videoFoundCard.visibility = View.VISIBLE
            videoFoundCard.animate().alpha(1f).setDuration(300).start()
            castFab.setImageResource(R.drawable.ic_cast_connected)
            castFab.setColorFilter(getColor(R.color.cast_button_color_connected))
            Timber.d("Video found: ${result.videoUrl} (${result.mimeType})")
        } else {
            videoFoundCard.visibility = View.GONE
            castFab.setImageResource(R.drawable.ic_cast)
            castFab.setColorFilter(getColor(R.color.cast_button_color))
            Timber.d("No video found: ${result.error}")
        }
    }

    private fun updateCastUI(state: CastState) {
        runOnUiThread {
            when (state) {
                is CastState.Connected -> {
                    castStatusBar.visibility = View.VISIBLE
                    castStatusText.text = getString(R.string.casting_to, castManager.getDeviceName() ?: "TV")
                    castStatusIcon.setImageResource(R.drawable.ic_cast_connected)
                    castFab.setImageResource(R.drawable.ic_cast_connected)
                    castFab.setColorFilter(getColor(R.color.cast_button_color_connected))
                    videoFoundCard.visibility = View.GONE
                }
                is CastState.Connecting -> {
                    castStatusBar.visibility = View.VISIBLE
                    castStatusText.text = getString(R.string.connecting)
                    castStatusIcon.setImageResource(R.drawable.ic_cast)
                }
                is CastState.Playing -> {
                    castStatusBar.visibility = View.VISIBLE
                    castStatusText.text = getString(R.string.casting_to, castManager.getDeviceName() ?: "TV")
                    castStatusIcon.setImageResource(R.drawable.ic_cast_connected)
                }
                is CastState.Paused -> {
                    castStatusBar.visibility = View.VISIBLE
                    castStatusText.text = "Paused on ${castManager.getDeviceName() ?: "TV"}"
                }
                is CastState.Buffering -> {
                    castStatusBar.visibility = View.VISIBLE
                    castStatusText.text = "Buffering..."
                }
                is CastState.Disconnected -> {
                    castStatusBar.visibility = View.GONE
                    castFab.setImageResource(R.drawable.ic_cast)
                    castFab.setColorFilter(getColor(R.color.cast_button_color))
                }
                is CastState.Error -> {
                    Snackbar.make(webView, state.message, Snackbar.LENGTH_LONG).show()
                    castStatusBar.visibility = View.GONE
                    castFab.setImageResource(R.drawable.ic_cast)
                    castFab.setColorFilter(getColor(R.color.cast_button_color))
                }
            }
        }
    }

    private fun showCastControls() {
        // Could open expanded controller or show bottom sheet
        CastManager.showCastDialog(this)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let { i ->
            val action = i.action
            val type = i.type
            if (Intent.ACTION_SEND == action && type != null) {
                if ("text/plain" == type) {
                    val sharedText = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    if (sharedText.isNotEmpty()) {
                        loadUrl(normalizeUrl(sharedText))
                    }
                } else if (type.startsWith("video/")) {
                    val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    uri?.let { loadUrl(it.toString()) }
                }
            } else if (Intent.ACTION_VIEW == action) {
                val uri = i.data
                uri?.let { loadUrl(it.toString()) }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun showSettings() {
        // Could open a settings fragment/activity
        Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun showAbout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.version))
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-extract video if page was already loaded
        if (webView.url != null && lastExtractedVideo == null) {
            extractVideoFromPage()
        }
    }

    override fun onDestroy() {
        castManager.cleanup()
        webView.destroy()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}