package com.nexal.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Health Connect opens this when someone asks why Nexal wants health data.
 * It shows the privacy policy, which covers what we read and write.
 */
class HealthPermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nexalfitness.com/privacy")))
        }
        finish()
    }
}
