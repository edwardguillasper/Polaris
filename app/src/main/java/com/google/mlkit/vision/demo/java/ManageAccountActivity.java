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
import android.widget.Toast;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.R;

/**
 * Profile editing screen (name, email, password, avatar), reached from the hamburger menu's
 * Account section.
 */
public final class ManageAccountActivity extends LocaleAwareActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_manage_account);

    int gradientStartColor = ContextCompat.getColor(this, R.color.polaris_menu_accent);
    int gradientEndColor = ContextCompat.getColor(this, R.color.polaris_blue_deep);

    findViewById(R.id.manage_account_back_button).setOnClickListener(v -> finish());

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.manage_account_title), gradientStartColor, gradientEndColor);
    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.manage_account_profile_heading), gradientStartColor, gradientEndColor);
    BrandGradient.applyLogoGradient(
        (ImageView) findViewById(R.id.manage_account_watermark),
        gradientStartColor,
        gradientEndColor);

    findViewById(R.id.manage_account_upload_button).setOnClickListener(v -> showPlaceholderToast());
    findViewById(R.id.manage_account_clear_button).setOnClickListener(v -> showPlaceholderToast());
    findViewById(R.id.manage_account_update_button).setOnClickListener(v -> showPlaceholderToast());
  }

  private void showPlaceholderToast() {
    Toast.makeText(this, R.string.login_placeholder_message, Toast.LENGTH_SHORT).show();
  }
}
