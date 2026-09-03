package com.google.mlkit.vision.demo

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.google.mlkit.vision.demo.java.ChooserActivity

class GetStartedActivity : LocaleAwareActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_get_started)

    findViewById<TextView>(R.id.get_started_button).setOnClickListener {
      startActivity(Intent(this, ChooserActivity::class.java))
    }
  }
}
