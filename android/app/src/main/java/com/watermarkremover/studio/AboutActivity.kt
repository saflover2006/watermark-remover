package com.watermarkremover.studio

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * AboutActivity — displays app version, privacy promise, feature list,
 * and build info. Launched from the overflow menu in NativeImageEditorActivity.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val versionText = findViewById<TextView>(R.id.aboutVersion)
        val backButton = findViewById<Button>(R.id.aboutBackButton)

        // Populate version string from the build info
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "0.1.0"
        }
        versionText.text = getString(R.string.about_version_label, versionName)

        backButton.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
    }
}
