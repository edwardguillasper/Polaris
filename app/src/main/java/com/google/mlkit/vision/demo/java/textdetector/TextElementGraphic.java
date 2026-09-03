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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.GraphicOverlay.Graphic;
import com.google.mlkit.vision.text.Text;

/** Draws a bounding box around a single recognized word and hit-tests taps against it. */
public class TextElementGraphic extends Graphic {

  private static final int MARKER_COLOR = Color.YELLOW;
  private static final float STROKE_WIDTH = 4.0f;

  private final Paint rectPaint;
  private final Text.Element element;

  public TextElementGraphic(GraphicOverlay overlay, Text.Element element) {
    super(overlay);
    this.element = element;

    rectPaint = new Paint();
    rectPaint.setColor(MARKER_COLOR);
    rectPaint.setStyle(Paint.Style.STROKE);
    rectPaint.setStrokeWidth(STROKE_WIDTH);
  }

  public String getText() {
    return element.getText();
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
    Rect box = element.getBoundingBox();
    if (box == null) {
      return;
    }
    canvas.drawRect(toViewRect(box), rectPaint);
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
