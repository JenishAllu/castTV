package com.instantbits.cast.webvideo.cast

import android.os.Bundle
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import com.instantbits.cast.webvideo.R

/**
 * Expanded controller activity for cast playback controls.
 * Shows full-screen controls when casting.
 */
class ExpandedControlsActivity : ExpandedControllerActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.expanded_controller)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.expanded_controller_menu, menu)
        return true
    }
}