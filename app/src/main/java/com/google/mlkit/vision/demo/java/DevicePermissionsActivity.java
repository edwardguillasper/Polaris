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

import android.content.Intent;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.R;

/**
 * Explains why Polaris needs camera/microphone access and links out to the system app-settings
 * screen, reached from the hamburger menu's "How you use Polaris" section.
 */
public final class DevicePermissionsActivity extends LocaleAwareActivity {

  private static final String BRAND_WORD = "Polaris";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_device_permissions);

    int gradientStartColor = ContextCompat.getColor(this, R.color.polaris_menu_accent);
    int gradientEndColor = ContextCompat.getColor(this, R.color.polaris_blue_deep);

    findViewById(R.id.device_permissions_back_button).setOnClickListener(v -> finish());

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.device_permissions_title), gradientStartColor, gradientEndColor);

    applyBrandMention(
        findViewById(R.id.device_permissions_heading),
        getString(R.string.device_permissions_heading),
        gradientStartColor,
        gradientEndColor);

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.device_permissions_subheading), gradientStartColor, gradientEndColor);

    applyBrandMention(
        findViewById(R.id.device_permissions_body),
        getString(R.string.device_permissions_body),
        gradientStartColor,
        gradientEndColor);

    TextView goToSettings = findViewById(R.id.device_permissions_go_to_settings);
    goToSettings.setPaintFlags(goToSettings.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
    BrandGradient.applyHorizontalGradient(goToSettings, gradientStartColor, gradientEndColor);
    goToSettings.setOnClickListener(v -> openAppSettings());

    BrandGradient.applyLogoGradient(
        (ImageView) findViewById(R.id.device_permissions_watermark),
        gradientStartColor,
        gradientEndColor);
  }

  /** Opens the phone's system "App info" screen for this app, scrolled to its permissions. */
  private void openAppSettings() {
    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    intent.setData(Uri.fromParts("package", getPackageName(), /* fragment= */ null));
    startActivity(intent);
  }

  /** Bolds and gradient-tints the first "Polaris" mention in {@code text}, if any. */
  private static void applyBrandMention(
      TextView textView, String text, int gradientStartColor, int gradientEndColor) {
    int start = text.indexOf(BRAND_WORD);
    if (start < 0) {
      return;
    }
    int end = start + BRAND_WORD.length();
    BrandGradient.applyRangeGradient(
        textView, text, start, end, gradientStartColor, gradientEndColor, true);
  }
}
