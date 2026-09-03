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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.core.content.ContextCompat;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.vision.demo.R;

/** Sign up screen reached from the hamburger menu's Add Account item or the Login screen. */
public final class SignUpActivity extends LocaleAwareActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_signup);

    int gradientStart = ContextCompat.getColor(this, R.color.polaris_blue_deep);
    int gradientEnd = ContextCompat.getColor(this, R.color.polaris_menu_accent);
    int[] labelIds = {
      R.id.signup_name_label,
      R.id.signup_gmail_label,
      R.id.signup_create_password_label,
      R.id.signup_confirm_password_label,
    };
    for (int labelId : labelIds) {
      applyHorizontalGradient(findViewById(labelId), gradientStart, gradientEnd);
    }

    TextView logInLink = findViewById(R.id.signup_log_in_link);
    underline(logInLink);
    logInLink.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));

    findViewById(R.id.signup_button)
        .setOnClickListener(
            v -> Toast.makeText(this, R.string.login_placeholder_message, Toast.LENGTH_SHORT).show());
  }

  private static void underline(TextView textView) {
    textView.setPaintFlags(textView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
  }

  private static void applyHorizontalGradient(TextView textView, int startColor, int endColor) {
    textView.post(
        () -> {
          float width = textView.getPaint().measureText(textView.getText().toString());
          if (width <= 0f) {
            return;
          }
          Shader shader =
              new LinearGradient(0, 0, width, 0, startColor, endColor, Shader.TileMode.CLAMP);
          textView.getPaint().setShader(shader);
          textView.invalidate();
        });
  }
}
