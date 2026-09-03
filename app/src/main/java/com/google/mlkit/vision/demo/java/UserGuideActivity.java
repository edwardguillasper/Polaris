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

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.R;

/** Static walkthrough of how to use each Polaris feature, reached from the hamburger menu. */
public final class UserGuideActivity extends LocaleAwareActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_user_guide);

    int gradientStartColor = ContextCompat.getColor(this, R.color.polaris_menu_accent);
    int gradientEndColor = ContextCompat.getColor(this, R.color.polaris_blue_deep);

    findViewById(R.id.user_guide_back_button).setOnClickListener(v -> finish());

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.user_guide_title), gradientStartColor, gradientEndColor);
    BrandGradient.applyWordGradient(
        findViewById(R.id.user_guide_heading),
        getString(R.string.user_guide_heading),
        "Polaris",
        gradientStartColor,
        gradientEndColor);
    BrandGradient.applyLogoGradient(
        (ImageView) findViewById(R.id.user_guide_watermark), gradientStartColor, gradientEndColor);

    LinearLayout sections = findViewById(R.id.user_guide_sections);
    LayoutInflater inflater = LayoutInflater.from(this);

    addSection(
        inflater,
        sections,
        R.string.user_guide_section_object_detection,
        boldThenRegular(
            R.string.user_guide_object_detection_bold, R.string.user_guide_object_detection_regular),
        gradientStartColor,
        gradientEndColor);
    addSection(
        inflater,
        sections,
        R.string.user_guide_section_text_to_speech,
        boldThenRegular(
            R.string.user_guide_text_to_speech_bold, R.string.user_guide_text_to_speech_regular),
        gradientStartColor,
        gradientEndColor);
    addSection(
        inflater,
        sections,
        R.string.user_guide_section_camera_access,
        regularThenBold(
            R.string.user_guide_camera_access_regular, R.string.user_guide_camera_access_bold),
        gradientStartColor,
        gradientEndColor);
    addSection(
        inflater,
        sections,
        R.string.user_guide_section_audio_feedback,
        bold(R.string.user_guide_audio_feedback_bold),
        gradientStartColor,
        gradientEndColor);
  }

  private void addSection(
      LayoutInflater inflater,
      LinearLayout sections,
      int headingRes,
      CharSequence body,
      int gradientStartColor,
      int gradientEndColor) {
    View section = inflater.inflate(R.layout.item_user_guide_section, sections, false);

    TextView headingView = section.findViewById(R.id.user_guide_section_heading);
    headingView.setText(headingRes);
    BrandGradient.applyHorizontalGradient(headingView, gradientStartColor, gradientEndColor);

    ((TextView) section.findViewById(R.id.user_guide_section_body)).setText(body);

    sections.addView(section);
  }

  private CharSequence boldThenRegular(int boldRes, int regularRes) {
    return TextUtils.concat(bold(boldRes), " ", getString(regularRes));
  }

  private CharSequence regularThenBold(int regularRes, int boldRes) {
    return TextUtils.concat(getString(regularRes), " ", bold(boldRes));
  }

  private CharSequence bold(int stringRes) {
    String text = getString(stringRes);
    SpannableString spannable = new SpannableString(text);
    spannable.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return spannable;
  }
}
