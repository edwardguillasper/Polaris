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

package com.google.mlkit.vision.demo.java.objectdetector;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.GraphicOverlay.Graphic;
import com.google.mlkit.vision.demo.R;

/**
 * Draws a small on-brand label bubble showing the color sampled from the crosshair/priority-area
 * region (color mode), anchored just above that region - same visual style and label-placement
 * approach as {@link ObjectGraphic}'s label, including clamping the pill to stay fully within the
 * visible screen bounds even when the crosshair sits near an edge.
 */
public class ColorLabelGraphic extends Graphic {

  // Design sizes in sp/dp rather than raw pixels - see ObjectGraphic's identical constants for
  // why (screen density and the user's system font-size accessibility setting otherwise get
  // ignored entirely).
  private static final float TEXT_SIZE_SP = 26.0f;
  private static final float LABEL_CORNER_RADIUS_DP = 10.0f;
  private static final float LABEL_PADDING_HORIZONTAL_DP = 14.0f;
  private static final float LABEL_PADDING_VERTICAL_DP = 10.0f;
  private static final float LABEL_MARGIN_DP = 10.0f;

  private final Rect crosshairImageRect;
  private final String colorLabel;
  private final Paint labelBackgroundPaint;
  private final Paint labelTextPaint;
  private final float labelCornerRadius;
  private final float labelPaddingHorizontal;
  private final float labelPaddingVertical;
  private final float labelMargin;

  public ColorLabelGraphic(GraphicOverlay overlay, String colorName, Rect crosshairImageRect) {
    super(overlay);
    this.crosshairImageRect = crosshairImageRect;
    this.colorLabel = Character.toUpperCase(colorName.charAt(0)) + colorName.substring(1);

    int accentColor = ContextCompat.getColor(getApplicationContext(), R.color.polaris_menu_accent);
    int labelTextColor =
        ContextCompat.getColor(getApplicationContext(), R.color.polaris_home_card_text);

    labelCornerRadius = dpToPixels(LABEL_CORNER_RADIUS_DP);
    labelPaddingHorizontal = dpToPixels(LABEL_PADDING_HORIZONTAL_DP);
    labelPaddingVertical = dpToPixels(LABEL_PADDING_VERTICAL_DP);
    labelMargin = dpToPixels(LABEL_MARGIN_DP);

    labelBackgroundPaint = new Paint();
    labelBackgroundPaint.setColor(accentColor);
    labelBackgroundPaint.setStyle(Paint.Style.FILL);
    labelBackgroundPaint.setAntiAlias(true);

    labelTextPaint = new Paint();
    labelTextPaint.setColor(labelTextColor);
    labelTextPaint.setTextSize(spToPixels(TEXT_SIZE_SP));
    labelTextPaint.setAntiAlias(true);
    labelTextPaint.setFakeBoldText(true);
  }

  @Override
  public void draw(Canvas canvas) {
    RectF rect = new RectF(crosshairImageRect);
    // If the image is flipped, the left will be translated to right, and the right to left.
    float x0 = translateX(rect.left);
    float x1 = translateX(rect.right);
    rect.left = Math.min(x0, x1);
    rect.right = Math.max(x0, x1);
    rect.top = translateY(rect.top);
    rect.bottom = translateY(rect.bottom);

    float textWidth = labelTextPaint.measureText(colorLabel);
    Paint.FontMetrics fontMetrics = labelTextPaint.getFontMetrics();
    float textHeight = fontMetrics.descent - fontMetrics.ascent;
    float pillWidth = textWidth + (2 * labelPaddingHorizontal);
    float pillHeight = textHeight + (2 * labelPaddingVertical);

    // Anchored centered above the crosshair region.
    float pillLeft = rect.left + (rect.width() - pillWidth) / 2f;
    float pillBottom = rect.top - labelMargin;
    float pillTop = pillBottom - pillHeight;

    // If the crosshair is near the top of the screen, there's no room to draw the label above
    // it, so draw it just inside the top of the crosshair region instead.
    if (pillTop < 0) {
      pillTop = rect.top + labelMargin;
      pillBottom = pillTop + pillHeight;
    }

    // Clamp to the visible canvas as a final step, exactly like ObjectGraphic's label - so this
    // pill never renders partially off-screen regardless of where the crosshair sits.
    pillLeft = ObjectGraphic.clampStart(pillLeft, pillWidth, canvas.getWidth());
    pillTop = ObjectGraphic.clampStart(pillTop, pillHeight, canvas.getHeight());
    float pillRight = pillLeft + pillWidth;
    pillBottom = pillTop + pillHeight;

    RectF labelRect = new RectF(pillLeft, pillTop, pillRight, pillBottom);
    canvas.drawRoundRect(labelRect, labelCornerRadius, labelCornerRadius, labelBackgroundPaint);
    canvas.drawText(
        colorLabel,
        pillLeft + labelPaddingHorizontal,
        pillBottom - labelPaddingVertical - fontMetrics.descent,
        labelTextPaint);
  }
}
