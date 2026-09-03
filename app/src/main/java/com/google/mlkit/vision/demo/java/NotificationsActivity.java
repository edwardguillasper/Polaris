/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.java;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.R;

/**
 * Empty-state notifications screen, reached from the hamburger menu's "How you use Polaris"
 * section.
 */
public final class NotificationsActivity extends LocaleAwareActivity {

  private static final String BRAND_WORD = "Polaris";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_notifications);

    int gradientStartColor = ContextCompat.getColor(this, R.color.polaris_menu_accent);
    int gradientEndColor = ContextCompat.getColor(this, R.color.polaris_blue_deep);

    findViewById(R.id.notifications_back_button).setOnClickListener(v -> finish());

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.notifications_title), gradientStartColor, gradientEndColor);

    applyBrandMention(
        findViewById(R.id.notifications_heading),
        getString(R.string.notifications_heading),
        gradientStartColor,
        gradientEndColor);

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.notifications_empty_heading), gradientStartColor, gradientEndColor);

    BrandGradient.applyLogoGradient(
        (ImageView) findViewById(R.id.notifications_watermark),
        gradientStartColor,
        gradientEndColor);
  }

  /** Bolds and gradient-tints the first "Polaris" mention in {@code text}, if any. */
  private static void applyBrandMention(
      TextView textView, String text, int gradientStartColor, int gradientEndColor) {
    int start = text.indexOf(BRAND_WORD);
    if (start < 0) {
      return;
    }
    int end = start + BRAND_WORD.length();
    // Fold a directly-adjacent "'" into the styled range, e.g. "Polaris'" in the possessive form.
    if (end < text.length() && text.charAt(end) == '\'') {
      end++;
    }
    BrandGradient.applyRangeGradient(
        textView, text, start, end, gradientStartColor, gradientEndColor, true);
  }
}
