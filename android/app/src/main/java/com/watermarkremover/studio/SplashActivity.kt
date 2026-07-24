package com.watermarkremover.studio

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.watermarkremover.studio.nativepreview.NativeImageEditorActivity

/**
 * SplashActivity — shown for a short duration on launch before the main editor.
 * Uses android:noHistory="true" so pressing back from the editor won't return here.
 *
 * Uses AppTheme.NoActionBar (not Theme.SplashScreen) to avoid requiring
 * installSplashScreen() which would crash if not called on API 31+.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Navigate to the main editor after a short brand moment
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                val intent = Intent(this, NativeImageEditorActivity::class.java)
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }, SPLASH_DELAY_MS)
    }

    companion object {
        private const val SPLASH_DELAY_MS = 1400L
    }
}
