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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.R;

/** Static Q&A list about Polaris, reached from the hamburger menu's Help section. */
public final class FaqActivity extends LocaleAwareActivity {

  private static final String BRAND_WORD = "Polaris";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_faq);

    int gradientStartColor = ContextCompat.getColor(this, R.color.polaris_menu_accent);
    int gradientEndColor = ContextCompat.getColor(this, R.color.polaris_blue_deep);

    findViewById(R.id.faq_back_button).setOnClickListener(v -> finish());

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.faq_title), gradientStartColor, gradientEndColor);
    applyBrandMention(
        findViewById(R.id.faq_heading), getString(R.string.faq_heading), gradientStartColor, gradientEndColor);
    BrandGradient.applyLogoGradient(
        (ImageView) findViewById(R.id.faq_watermark), gradientStartColor, gradientEndColor);

    LinearLayout list = findViewById(R.id.faq_list);
    LayoutInflater inflater = LayoutInflater.from(this);

    addQuestion(
        inflater, list, R.string.faq_question_1, R.string.faq_answer_1, gradientStartColor, gradientEndColor);
    addQuestion(
        inflater, list, R.string.faq_question_2, R.string.faq_answer_2, gradientStartColor, gradientEndColor);
    addQuestion(
        inflater, list, R.string.faq_question_3, R.string.faq_answer_3, gradientStartColor, gradientEndColor);
    addQuestion(
        inflater, list, R.string.faq_question_4, R.string.faq_answer_4, gradientStartColor, gradientEndColor);
  }

  private void addQuestion(
      LayoutInflater inflater,
      LinearLayout list,
      int questionRes,
      int answerRes,
      int gradientStartColor,
      int gradientEndColor) {
    View item = inflater.inflate(R.layout.item_faq, list, false);

    TextView questionView = item.findViewById(R.id.faq_item_question);
    String question = getString(questionRes);
    questionView.setText(question);
    applyBrandMention(questionView, question, gradientStartColor, gradientEndColor);

    ((TextView) item.findViewById(R.id.faq_item_answer)).setText(getString(answerRes));

    list.addView(item);
  }

  /** Bolds and gradient-tints the first "Polaris" mention in {@code text}, if any. */
  private static void applyBrandMention(
      TextView textView, String text, int gradientStartColor, int gradientEndColor) {
    int start = text.indexOf(BRAND_WORD);
    if (start < 0) {
      return;
    }
    int end = start + BRAND_WORD.length();
    // Fold a directly-adjacent "?" into the styled range, e.g. "Polaris?" in "What is Polaris?".
    if (end < text.length() && text.charAt(end) == '?') {
      end++;
    }
    BrandGradient.applyRangeGradient(
        textView, text, start, end, gradientStartColor, gradientEndColor, true);
  }
}
