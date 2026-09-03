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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.text.style.UpdateAppearance;
import android.widget.ImageView;
import android.widget.TextView;

/** Applies Polaris's teal-to-blue brand gradient to text and the logo outline. */
public final class BrandGradient {

  private BrandGradient() {}

  /** Tints the full width of a {@link TextView}'s text with a horizontal gradient. */
  public static void applyHorizontalGradient(TextView textView, int startColor, int endColor) {
    textView.post(
        () -> {
          float width = textView.getPaint().measureText(textView.getText().toString());
          if (width <= 0f) {
            return;
          }
          Shader shader = new LinearGradient(0, 0, width, 0, startColor, endColor, Shader.TileMode.CLAMP);
          textView.getPaint().setShader(shader);
          textView.invalidate();
        });
  }

  /** Tints only {@code word} within {@code fullText} with a horizontal gradient. */
  public static void applyWordGradient(
      TextView textView, String fullText, String word, int startColor, int endColor) {
    int start = fullText.indexOf(word);
    if (start < 0) {
      return;
    }
    applyRangeGradient(textView, fullText, start, start + word.length(), startColor, endColor, false);
  }

  /**
   * Tints {@code fullText[start, end)} with a horizontal gradient, optionally bolding it too -
   * e.g. for a brand mention embedded in an otherwise plain sentence.
   */
  public static void applyRangeGradient(
      TextView textView,
      String fullText,
      int start,
      int end,
      int startColor,
      int endColor,
      boolean bold) {
    // A Shader's coordinates are in the TextView's own canvas space, not span-local, so the
    // gradient's start/end x must come from where the word actually lands after line wrapping
    // and centering are resolved - only the Layout (available once this has been measured) knows
    // that.
    textView.post(
        () -> {
          Layout layout = textView.getLayout();
          if (layout == null) {
            return;
          }
          float x0 = layout.getPrimaryHorizontal(start);
          float x1 = layout.getPrimaryHorizontal(end);
          Shader shader = new LinearGradient(x0, 0, x1, 0, startColor, endColor, Shader.TileMode.CLAMP);

          SpannableString spannable = new SpannableString(fullText);
          spannable.setSpan(new ShaderSpan(shader), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
          if (bold) {
            spannable.setSpan(
                new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
          }
          textView.setText(spannable);
        });
  }

  /** Recolors an outline-style logo {@link ImageView} with a horizontal gradient. */
  public static void applyLogoGradient(ImageView imageView, int startColor, int endColor) {
    BitmapDrawable drawable = (BitmapDrawable) imageView.getDrawable();
    Bitmap source = drawable.getBitmap();
    Bitmap tinted = source.copy(Bitmap.Config.ARGB_8888, true);

    Canvas canvas = new Canvas(tinted);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setShader(
        new LinearGradient(0, 0, tinted.getWidth(), 0, startColor, endColor, Shader.TileMode.CLAMP));
    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    canvas.drawRect(0, 0, tinted.getWidth(), tinted.getHeight(), paint);

    imageView.setImageBitmap(tinted);
  }

  /** Applies a fixed {@link Shader} to a span's text paint, e.g. for word-level gradient text. */
  private static final class ShaderSpan extends CharacterStyle implements UpdateAppearance {
    private final Shader shader;

    ShaderSpan(Shader shader) {
      this.shader = shader;
    }

    @Override
    public void updateDrawState(TextPaint textPaint) {
      textPaint.setShader(shader);
    }
  }
}
