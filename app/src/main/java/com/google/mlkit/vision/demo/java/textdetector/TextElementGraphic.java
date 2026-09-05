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

package com.google.mlkit.vision.demo.java.textdetector;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.GraphicOverlay.Graphic;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.text.Text;

/**
 * Draws an on-brand highlight around a single recognized word (a subtle accent-color wash plus a
 * thin rounded outline, matching the reticle style in {@link com.google.mlkit.vision.demo.CrosshairGraphic})
 * and hit-tests taps against it. The highlight is only actually painted while {@link #setActive}
 * has been set true - by default a word is tracked (for hit-testing and read-aloud sequencing)
 * but invisible, so the screen doesn't end up with every detected word boxed at once.
 */
public class TextElementGraphic extends Graphic {

  private static final float CORNER_RADIUS = 6.0f;
  private static final float STROKE_WIDTH = 3.0f;

  // Shrinks each word's raw ML Kit box slightly so adjacent words' highlights don't visually
  // touch or overlap.
  private static final float BOX_INSET = 2.0f;

  // ~16% opacity - a subtle wash rather than a solid block.
  private static final int FILL_ALPHA = 40;

  private final Paint fillPaint;
  private final Paint strokePaint;
  private final Text.Element element;
  private boolean active;

  public TextElementGraphic(GraphicOverlay overlay, Text.Element element) {
    super(overlay);
    this.element = element;

    int accentColor = ContextCompat.getColor(getApplicationContext(), R.color.polaris_menu_accent);

    fillPaint = new Paint();
    fillPaint.setAntiAlias(true);
    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setColor(accentColor);
    fillPaint.setAlpha(FILL_ALPHA);

    strokePaint = new Paint();
    strokePaint.setAntiAlias(true);
    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeWidth(STROKE_WIDTH);
    strokePaint.setColor(accentColor);
  }

  public String getText() {
    return element.getText();
  }

  /** Whether this word's highlight should currently be painted (see class doc). */
  public void setActive(boolean active) {
    this.active = active;
  }

  /** Returns true if the given view-space point falls within this word's transformed box. */
  public boolean contains(float x, float y) {
    Rect box = element.getBoundingBox();
    if (box == null) {
      return false;
    }
    return toViewRect(box).contains(x, y);
  }

  @Override
  public void draw(Canvas canvas) {
    if (!active) {
      return;
    }
    Rect box = element.getBoundingBox();
    if (box == null) {
      return;
    }
    RectF rect = toViewRect(box);
    rect.inset(BOX_INSET, BOX_INSET);
    canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, fillPaint);
    canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, strokePaint);
  }

  private RectF toViewRect(Rect box) {
    RectF rect = new RectF(box);
    // If the image is flipped, the left will be translated to right, and the right to left.
    float x0 = translateX(rect.left);
    float x1 = translateX(rect.right);
    rect.left = Math.min(x0, x1);
    rect.right = Math.max(x0, x1);
    rect.top = translateY(rect.top);
    rect.bottom = translateY(rect.bottom);
    return rect;
  }
}
