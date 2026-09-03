package com.google.mlkit.vision.demo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class SplashActivity : LocaleAwareActivity() {

  private val handler = Handler(Looper.getMainLooper())
  private val goToGetStarted = Runnable {
    startActivity(Intent(this, GetStartedActivity::class.java))
    overridePendingTransition(R.anim.splash_fade_in, R.anim.splash_fade_out)
    finish()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_splash)
    handler.postDelayed(goToGetStarted, SPLASH_DURATION_MS)
  }

  override fun onDestroy() {
    handler.removeCallbacks(goToGetStarted)
    super.onDestroy()
  }

  companion object {
    private const val SPLASH_DURATION_MS = 1800L
  }
}
