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

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.util.Log;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * Cheaply estimates the dominant color of the crosshair/priority-area region of a YUV_420_888
 * camera frame by sampling a small grid of pixels directly from the raw Y/U/V planes, rather than
 * decoding the whole frame to a Bitmap. This keeps per-detection cost to a couple dozen array
 * reads, safe to run on every frame in a live STREAM_MODE pipeline.
 *
 * <p>{@link #sampleCrosshairColor} is deliberately the only public entry point, and it always
 * derives its own sample region from {@link CrosshairGraphic#computeImageRect} rather than
 * accepting one from the caller - so there is no way for any caller to have this sample anything
 * other than the crosshair region, regardless of any other state or condition.
 */
public final class FrameColorSampler {

  private static final String TAG = "FrameColorSampler";

  // 5x5 = 25 samples per bounding box - enough to average out noise, cheap enough to be free.
  private static final int SAMPLE_GRID_SIZE = 5;

  // Shrinks the sampled area toward the box's center so background bleeding past an imprecise
  // detection box doesn't skew the color average.
  private static final float BOX_INSET_FRACTION = 0.15f;

  private FrameColorSampler() {}

  /**
   * Returns a simple color name (e.g. "red", "blue") for the dominant color sampled from the
   * crosshair/priority-area region ({@link CrosshairGraphic#computeImageRect}, {@link
   * CrosshairGraphic.Shape#SQUARE}), or {@code null} if sampling isn't possible for this frame
   * (image size not yet known, unsupported format, camera HAL quirk, etc).
   */
  @Nullable
  public static String sampleCrosshairColor(
      Image mediaImage, int rotationDegrees, GraphicOverlay overlay) {
    Rect crosshairRect = CrosshairGraphic.computeImageRect(overlay, CrosshairGraphic.Shape.SQUARE);
    if (crosshairRect == null) {
      return null;
    }
    try {
      return sampleColorNameUnsafe(mediaImage, rotationDegrees, crosshairRect);
    } catch (RuntimeException e) {
      // Camera HALs vary in how strictly they follow the YUV_420_888 plane contract; never let a
      // quirky device turn this best-effort feature into a crash of the live detection pipeline.
      Log.w(TAG, "Color sampling failed, skipping color for this detection", e);
      return null;
    }
  }

  @Nullable
  private static String sampleColorNameUnsafe(
      Image mediaImage, int rotationDegrees, Rect boundingBox) {
    if (mediaImage.getFormat() != ImageFormat.YUV_420_888) {
      return null;
    }
    Image.Plane[] planes = mediaImage.getPlanes();
    if (planes.length < 3) {
      return null;
    }

    int sensorWidth = mediaImage.getWidth();
    int sensorHeight = mediaImage.getHeight();

    ByteBuffer yBuffer = planes[0].getBuffer();
    int yRowStride = planes[0].getRowStride();
    int yPixelStride = planes[0].getPixelStride();
    ByteBuffer uBuffer = planes[1].getBuffer();
    int uRowStride = planes[1].getRowStride();
    int uPixelStride = planes[1].getPixelStride();
    ByteBuffer vBuffer = planes[2].getBuffer();
    int vRowStride = planes[2].getRowStride();
    int vPixelStride = planes[2].getPixelStride();

    int insetX = (int) (boundingBox.width() * BOX_INSET_FRACTION);
    int insetY = (int) (boundingBox.height() * BOX_INSET_FRACTION);
    int left = boundingBox.left + insetX;
    int right = boundingBox.right - insetX;
    int top = boundingBox.top + insetY;
    int bottom = boundingBox.bottom - insetY;
    if (right <= left || bottom <= top) {
      // The inset ate the whole box (a tiny detection); fall back to the box as-is.
      left = boundingBox.left;
      right = boundingBox.right;
      top = boundingBox.top;
      bottom = boundingBox.bottom;
    }
    if (right <= left || bottom <= top) {
      return null;
    }

    long sumR = 0;
    long sumG = 0;
    long sumB = 0;
    int sampleCount = 0;

    for (int gy = 0; gy < SAMPLE_GRID_SIZE; gy++) {
      float fy = (gy + 0.5f) / SAMPLE_GRID_SIZE;
      int yUpright = top + Math.round(fy * (bottom - top));
      for (int gx = 0; gx < SAMPLE_GRID_SIZE; gx++) {
        float fx = (gx + 0.5f) / SAMPLE_GRID_SIZE;
        int xUpright = left + Math.round(fx * (right - left));

        // ML Kit reports bounding boxes in the "upright" (already-rotated) coordinate space, but
        // the raw YUV planes are still in the sensor's native (unrotated) orientation - map back.
        int xSensor;
        int ySensor;
        switch (rotationDegrees) {
          case 90:
            xSensor = yUpright;
            ySensor = sensorHeight - 1 - xUpright;
            break;
          case 180:
            xSensor = sensorWidth - 1 - xUpright;
            ySensor = sensorHeight - 1 - yUpright;
            break;
          case 270:
            xSensor = sensorWidth - 1 - yUpright;
            ySensor = xUpright;
            break;
          default:
            xSensor = xUpright;
            ySensor = yUpright;
            break;
        }

        if (xSensor < 0 || xSensor >= sensorWidth || ySensor < 0 || ySensor >= sensorHeight) {
          continue;
        }

        int yIndex = ySensor * yRowStride + xSensor * yPixelStride;
        int chromaX = xSensor / 2;
        int chromaY = ySensor / 2;
        int uIndex = chromaY * uRowStride + chromaX * uPixelStride;
        int vIndex = chromaY * vRowStride + chromaX * vPixelStride;
        if (yIndex < 0
            || yIndex >= yBuffer.limit()
            || uIndex < 0
            || uIndex >= uBuffer.limit()
            || vIndex < 0
            || vIndex >= vBuffer.limit()) {
          continue;
        }

        int yValue = yBuffer.get(yIndex) & 0xFF;
        int uValue = (uBuffer.get(uIndex) & 0xFF) - 128;
        int vValue = (vBuffer.get(vIndex) & 0xFF) - 128;

        sumR += clamp(Math.round(yValue + 1.402f * vValue));
        sumG += clamp(Math.round(yValue - 0.344136f * uValue - 0.714136f * vValue));
        sumB += clamp(Math.round(yValue + 1.772f * uValue));
        sampleCount++;
      }
    }

    if (sampleCount == 0) {
      return null;
    }

    return ColorNamer.nameOf(
        (int) (sumR / sampleCount), (int) (sumG / sampleCount), (int) (sumB / sampleCount));
  }

  private static int clamp(int value) {
    return Math.max(0, Math.min(255, value));
  }
}
