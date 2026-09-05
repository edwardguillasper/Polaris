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

package com.google.mlkit.vision.demo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.GraphicOverlay.Graphic;

/**
 * Draws a fixed center reticle over the preview, styled in Polaris's brand accent color. Shared by
 * every live-preview screen that supports crosshair targeting (Object Detection, Text-to-Speech).
 * Also defines the image-space region ({@link #computeImageRect}) used to decide which detections
 * count as "in the crosshair" when crosshair mode is on - kept here so the visual reticle and the
 * actual filtering logic, in each screen's processor, always agree on exactly the same area.
 */
public class CrosshairGraphic extends Graphic {

  /** The reticle's proportions: a square (framing one object) or a wide band (framing a line of text). */
  public enum Shape {
    SQUARE,
    HORIZONTAL_RECTANGLE
  }

  // SQUARE: covers this fraction of the shorter image dimension, in both directions, centered on
  // the frame - unchanged from the original single-shape reticle.
  private static final float SQUARE_SIZE_FRACTION = 0.35f;

  // HORIZONTAL_RECTANGLE: a wide, short band suited to framing a line of text rather than a
  // single object - most of the image's width, a modest slice of its height.
  private static final float HORIZONTAL_RECT_WIDTH_FRACTION = 0.8f;
  private static final float HORIZONTAL_RECT_HEIGHT_FRACTION = 0.22f;

  private static final float STROKE_WIDTH = 5.0f;
  private static final float CORNER_LENGTH = 36.0f;
  private static final float CENTER_MARK_LENGTH = 14.0f;

  private final GraphicOverlay overlay;
  private final Shape shape;
  private final Paint reticlePaint;

  public CrosshairGraphic(GraphicOverlay overlay, Shape shape) {
    super(overlay);
    this.overlay = overlay;
    this.shape = shape;

    reticlePaint = new Paint();
    reticlePaint.setColor(
        ContextCompat.getColor(getApplicationContext(), R.color.polaris_menu_accent));
    reticlePaint.setStyle(Paint.Style.STROKE);
    reticlePaint.setStrokeWidth(STROKE_WIDTH);
    reticlePaint.setStrokeCap(Paint.Cap.ROUND);
    reticlePaint.setAntiAlias(true);
  }

  /**
   * The crosshair's target region in image coordinates - the same coordinate space as detection
   * bounding boxes (e.g. {@code DetectedObject.getBoundingBox()}, {@code Text.Line.getBoundingBox()})
   * - or null if the overlay doesn't know the image size yet.
   */
  @Nullable
  public static Rect computeImageRect(GraphicOverlay overlay, Shape shape) {
    int imageWidth = overlay.getImageWidth();
    int imageHeight = overlay.getImageHeight();
    if (imageWidth <= 0 || imageHeight <= 0) {
      return null;
    }
    float halfWidth;
    float halfHeight;
    if (shape == Shape.HORIZONTAL_RECTANGLE) {
      halfWidth = HORIZONTAL_RECT_WIDTH_FRACTION * imageWidth / 2f;
      halfHeight = HORIZONTAL_RECT_HEIGHT_FRACTION * imageHeight / 2f;
    } else {
      float halfSize = SQUARE_SIZE_FRACTION * Math.min(imageWidth, imageHeight) / 2f;
      halfWidth = halfSize;
      halfHeight = halfSize;
    }
    float centerX = imageWidth / 2f;
    float centerY = imageHeight / 2f;
    return new Rect(
        (int) (centerX - halfWidth),
        (int) (centerY - halfHeight),
        (int) (centerX + halfWidth),
        (int) (centerY + halfHeight));
  }

  @Override
  public void draw(Canvas canvas) {
    Rect imageRect = computeImageRect(overlay, shape);
    if (imageRect == null) {
      return;
    }

    RectF rect = new RectF(imageRect);
    // If the image is flipped, the left will be translated to right, and the right to left.
    float x0 = translateX(rect.left);
    float x1 = translateX(rect.right);
    rect.left = Math.min(x0, x1);
    rect.right = Math.max(x0, x1);
    rect.top = translateY(rect.top);
    rect.bottom = translateY(rect.bottom);

    // Four corner brackets rather than a full box, so this reads as a viewfinder reticle rather
    // than another detection box.
    drawCornerBracket(canvas, rect.left, rect.top, 1, 1);
    drawCornerBracket(canvas, rect.right, rect.top, -1, 1);
    drawCornerBracket(canvas, rect.left, rect.bottom, 1, -1);
    drawCornerBracket(canvas, rect.right, rect.bottom, -1, -1);

    float centerX = (rect.left + rect.right) / 2f;
    float centerY = (rect.top + rect.bottom) / 2f;
    canvas.drawLine(
        centerX - CENTER_MARK_LENGTH, centerY, centerX + CENTER_MARK_LENGTH, centerY, reticlePaint);
    canvas.drawLine(
        centerX, centerY - CENTER_MARK_LENGTH, centerX, centerY + CENTER_MARK_LENGTH, reticlePaint);
  }

  /** Draws one L-shaped bracket anchored at ({@code x}, {@code y}), pointing inward. */
  private void drawCornerBracket(Canvas canvas, float x, float y, int dirX, int dirY) {
    canvas.drawLine(x, y, x + (CORNER_LENGTH * dirX), y, reticlePaint);
    canvas.drawLine(x, y, x, y + (CORNER_LENGTH * dirY), reticlePaint);
  }
}
