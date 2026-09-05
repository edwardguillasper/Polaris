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

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Checks and requests runtime CAMERA permission. Shared by every screen that binds a live camera
 * preview (Object Detection, Text-to-Speech/OCR), which previously assumed this permission had
 * already been granted out-of-band instead of asking for it.
 */
public final class CameraPermission {

  private CameraPermission() {}

  public static boolean isGranted(Context context) {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED;
  }

  public static void request(Activity activity, int requestCode) {
    ActivityCompat.requestPermissions(activity, new String[] {Manifest.permission.CAMERA}, requestCode);
  }
}
