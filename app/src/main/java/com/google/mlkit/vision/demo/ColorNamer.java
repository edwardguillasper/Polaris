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

import android.graphics.Color;

/**
 * Maps an RGB color to one of a small set of common, human-readable color names (red, blue,
 * brown, etc). This is a coarse HSV-bucket heuristic, not colorimetry - good enough for spoken
 * feedback like "red bag", not for precise color matching.
 */
public final class ColorNamer {

  private ColorNamer() {}

  public static String nameOf(int r, int g, int b) {
    float[] hsv = new float[3];
    Color.RGBToHSV(r, g, b, hsv);
    float hue = hsv[0]; // [0, 360)
    float saturation = hsv[1]; // [0, 1]
    float value = hsv[2]; // [0, 1]

    if (value < 0.3f) {
      // A genuinely black object rarely renders at near-zero brightness even under realistic
      // indoor lighting - ambient light, sensor noise floor, and auto-exposure all lift it well
      // above 0, the same way a white surface rarely blows out to near-1.0 (see the white/gray
      // split below). Without enough headroom here, real black objects land above this cutoff
      // and get named "gray" instead.
      return "black";
    }
    if (saturation < 0.18f) {
      // Real-world white-balance/color-temperature casts commonly leave a genuinely neutral
      // surface (white paper, gray concrete) with a bit more saturation than you'd expect -
      // often a warm yellow/orange tinge under indoor lighting - so this needs enough tolerance
      // to still catch those as achromatic rather than letting them leak into the hue-based
      // branches below and get named "orange"/"brown" instead of "white"/"gray".
      //
      // Real-world lighting also rarely blows a white surface all the way out to near-1.0
      // brightness, so the white/gray split sits at a middling brightness rather than
      // near-pure-white - but not so low that an ordinary mid-gray surface clears the bar too.
      return value > 0.6f ? "white" : "gray";
    }
    // Brown reads as a dark, moderately-saturated orange/red rather than a hue of its own.
    if (hue < 45f && value < 0.6f && saturation > 0.35f) {
      return "brown";
    }
    if (hue < 15f || hue >= 345f) {
      return "red";
    }
    if (hue < 45f) {
      return "orange";
    }
    if (hue < 70f) {
      return "yellow";
    }
    if (hue < 170f) {
      return "green";
    }
    if (hue < 200f) {
      return "cyan";
    }
    if (hue < 260f) {
      return "blue";
    }
    if (hue < 290f) {
      return "purple";
    }
    return "pink";
  }
}
