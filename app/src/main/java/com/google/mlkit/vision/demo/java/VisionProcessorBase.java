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

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.Build.VERSION_CODES;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskExecutors;
import com.google.android.gms.tasks.Tasks;
import com.google.android.odml.image.BitmapMlImageBuilder;
import com.google.android.odml.image.ByteBufferMlImageBuilder;
import com.google.android.odml.image.MediaMlImageBuilder;
import com.google.android.odml.image.MlImage;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.demo.BitmapUtils;
import com.google.mlkit.vision.demo.CameraImageGraphic;
import com.google.mlkit.vision.demo.FrameMetadata;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.ScopedExecutor;
import com.google.mlkit.vision.demo.TemperatureMonitor;
import com.google.mlkit.vision.demo.VisionImageProcessor;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Abstract base class for vision frame processors. Subclasses need to implement {@link
 * #onSuccess(Object, GraphicOverlay)} to define what they want to with the detection results and
 * {@link #detectInImage(InputImage)} to specify the detector object.
 *
 * @param <T> The type of the detected feature.
 */
public abstract class VisionProcessorBase<T> implements VisionImageProcessor {

  protected static final String MANUAL_TESTING_LOG = "LogTagForTest";
  private static final String TAG = "VisionProcessorBase";

  private final ActivityManager activityManager;
  private final Timer fpsTimer = new Timer();
  private final ScopedExecutor executor;
  private final TemperatureMonitor temperatureMonitor;

  // Whether this processor is already shut down
  private boolean isShutdown;

  // Caps how often a CameraX frame is dispatched to the detector. Running inference on every
  // camera frame is unnecessary for live detection and burns CPU/battery for no benefit once the
  // capture rate exceeds what's needed to keep results feeling live.
  private static final long MIN_DETECTION_INTERVAL_MS = 100L; // ~10 fps cap
  private long lastDetectionDispatchedAtMs = 0L;

  // Used to calculate latency, running in the same thread, no sync needed.
  private int numRuns = 0;
  private long totalFrameMs = 0;
  private long maxFrameMs = 0;
  private long minFrameMs = Long.MAX_VALUE;
  private long totalDetectorMs = 0;
  private long maxDetectorMs = 0;
  private long minDetectorMs = Long.MAX_VALUE;

  // Frame count that have been processed so far in an one second interval, so latency stats are
  // logged (to Logcat only, not shown on screen) at most once per second rather than every frame.
  private int frameProcessedInOneSecondInterval = 0;

  // To keep the latest images and its metadata.
  @GuardedBy("this")
  private ByteBuffer latestImage;

  @GuardedBy("this")
  private FrameMetadata latestImageMetaData;
  // To keep the images and metadata in process.
  @GuardedBy("this")
  private ByteBuffer processingImage;

  @GuardedBy("this")
  private FrameMetadata processingMetaData;

  // The raw camera frame backing the most recent detection results, kept only so a subclass can
  // cheaply sample pixel colors within a detection's bounding box during onSuccess() - see
  // getLatestMediaImageForColorSampling(). Only ever set from the CameraX ImageProxy path below;
  // both the write (in processImageProxy) and the read (during onSuccess, invoked through the
  // MAIN_THREAD executor) happen on the main thread, and CameraX won't deliver a new frame until
  // this one is closed (after onSuccess returns), so no extra synchronization is needed here.
  @Nullable private Image latestMediaImageForColorSampling;
  private int latestMediaImageRotationDegrees;

  protected VisionProcessorBase(Context context) {
    activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    executor = new ScopedExecutor(TaskExecutors.MAIN_THREAD);
    fpsTimer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            frameProcessedInOneSecondInterval = 0;
          }
        },
        /* delay= */ 0,
        /* period= */ 1000);
    temperatureMonitor = new TemperatureMonitor(context);
  }

  // -----------------Code for processing single still image----------------------------------------
  @Override
  public void processBitmap(Bitmap bitmap, final GraphicOverlay graphicOverlay) {
    long frameStartMs = SystemClock.elapsedRealtime();
    latestMediaImageForColorSampling = null;

    if (isMlImageEnabled(graphicOverlay.getContext())) {
      MlImage mlImage = new BitmapMlImageBuilder(bitmap).build();
      requestDetectInImage(mlImage, graphicOverlay, /* originalCameraImage= */ null, frameStartMs);
      mlImage.close();

      return;
    }

    requestDetectInImage(
        InputImage.fromBitmap(bitmap, 0),
        graphicOverlay,
        /* originalCameraImage= */ null,
        frameStartMs);
  }

  // -----------------Code for processing live preview frame from Camera1 API-----------------------
  @Override
  public synchronized void processByteBuffer(
      ByteBuffer data, final FrameMetadata frameMetadata, final GraphicOverlay graphicOverlay) {
    latestImage = data;
    latestImageMetaData = frameMetadata;
    if (processingImage == null && processingMetaData == null) {
      processLatestImage(graphicOverlay);
    }
  }

  private synchronized void processLatestImage(final GraphicOverlay graphicOverlay) {
    processingImage = latestImage;
    processingMetaData = latestImageMetaData;
    latestImage = null;
    latestImageMetaData = null;
    if (processingImage != null && processingMetaData != null && !isShutdown) {
      processImage(processingImage, processingMetaData, graphicOverlay);
    }
  }

  private void processImage(
      ByteBuffer data, final FrameMetadata frameMetadata, final GraphicOverlay graphicOverlay) {
    long frameStartMs = SystemClock.elapsedRealtime();
    latestMediaImageForColorSampling = null;

    // If live viewport is on (that is the underneath surface view takes care of the camera preview
    // drawing), skip the unnecessary bitmap creation that used for the manual preview drawing.
    Bitmap bitmap =
        PreferenceUtils.isCameraLiveViewportEnabled(graphicOverlay.getContext())
            ? null
            : BitmapUtils.getBitmap(data, frameMetadata);

    if (isMlImageEnabled(graphicOverlay.getContext())) {
      MlImage mlImage =
          new ByteBufferMlImageBuilder(
                  data,
                  frameMetadata.getWidth(),
                  frameMetadata.getHeight(),
                  MlImage.IMAGE_FORMAT_NV21)
              .setRotation(frameMetadata.getRotation())
              .build();

      requestDetectInImage(mlImage, graphicOverlay, bitmap, frameStartMs)
          .addOnSuccessListener(executor, results -> processLatestImage(graphicOverlay));

      // This is optional. Java Garbage collection can also close it eventually.
      mlImage.close();
      return;
    }

    requestDetectInImage(
            InputImage.fromByteBuffer(
                data,
                frameMetadata.getWidth(),
                frameMetadata.getHeight(),
                frameMetadata.getRotation(),
                InputImage.IMAGE_FORMAT_NV21),
            graphicOverlay,
            bitmap,
            frameStartMs)
        .addOnSuccessListener(executor, results -> processLatestImage(graphicOverlay));
  }

  // -----------------Code for processing live preview frame from CameraX API-----------------------
  @Override
  @RequiresApi(VERSION_CODES.LOLLIPOP)
  @ExperimentalGetImage
  public void processImageProxy(ImageProxy image, GraphicOverlay graphicOverlay) {
    long frameStartMs = SystemClock.elapsedRealtime();
    if (isShutdown) {
      image.close();
      return;
    }

    if (frameStartMs - lastDetectionDispatchedAtMs < MIN_DETECTION_INTERVAL_MS) {
      // Drop this frame to throttle the detection rate. CameraX's KEEP_ONLY_LATEST backpressure
      // strategy will hand us the next available frame once this one is closed.
      image.close();
      return;
    }
    lastDetectionDispatchedAtMs = frameStartMs;

    latestMediaImageForColorSampling = image.getImage();
    latestMediaImageRotationDegrees = image.getImageInfo().getRotationDegrees();

    Bitmap bitmap = null;
    if (!PreferenceUtils.isCameraLiveViewportEnabled(graphicOverlay.getContext())) {
      bitmap = BitmapUtils.getBitmap(image);
    }

    if (isMlImageEnabled(graphicOverlay.getContext())) {
      MlImage mlImage =
          new MediaMlImageBuilder(image.getImage())
              .setRotation(image.getImageInfo().getRotationDegrees())
              .build();

      requestDetectInImage(mlImage, graphicOverlay, /* originalCameraImage= */ bitmap, frameStartMs)
          // When the image is from CameraX analysis use case, must call image.close() on received
          // images when finished using them. Otherwise, new images may not be received or the
          // camera may stall.
          // Currently MlImage doesn't support ImageProxy directly, so we still need to call
          // ImageProxy.close() here.
          .addOnCompleteListener(results -> image.close());
      return;
    }

    requestDetectInImage(
            InputImage.fromMediaImage(image.getImage(), image.getImageInfo().getRotationDegrees()),
            graphicOverlay,
            /* originalCameraImage= */ bitmap,
            frameStartMs)
        // When the image is from CameraX analysis use case, must call image.close() on received
        // images when finished using them. Otherwise, new images may not be received or the camera
        // may stall.
        .addOnCompleteListener(results -> image.close());
  }

  // -----------------Common processing logic-------------------------------------------------------
  private Task<T> requestDetectInImage(
      final InputImage image,
      final GraphicOverlay graphicOverlay,
      @Nullable final Bitmap originalCameraImage,
      long frameStartMs) {
    return setUpListener(detectInImage(image), graphicOverlay, originalCameraImage, frameStartMs);
  }

  private Task<T> requestDetectInImage(
      final MlImage image,
      final GraphicOverlay graphicOverlay,
      @Nullable final Bitmap originalCameraImage,
      long frameStartMs) {
    return setUpListener(detectInImage(image), graphicOverlay, originalCameraImage, frameStartMs);
  }

  private Task<T> setUpListener(
      Task<T> task,
      final GraphicOverlay graphicOverlay,
      @Nullable final Bitmap originalCameraImage,
      long frameStartMs) {
    final long detectorStartMs = SystemClock.elapsedRealtime();
    return task.addOnSuccessListener(
            executor,
            results -> {
              if (isPaused()) {
                // Discard a result from a frame that was already in flight when a subclass
                // paused (e.g. to keep a graphic's on-screen position stable while it's being
                // read aloud) - applying it now would silently clobber whatever state the
                // subclass is relying on staying put.
                return;
              }

              long endMs = SystemClock.elapsedRealtime();
              long currentFrameLatencyMs = endMs - frameStartMs;
              long currentDetectorLatencyMs = endMs - detectorStartMs;
              if (numRuns >= 500) {
                resetLatencyStats();
              }
              numRuns++;
              frameProcessedInOneSecondInterval++;
              totalFrameMs += currentFrameLatencyMs;
              maxFrameMs = max(currentFrameLatencyMs, maxFrameMs);
              minFrameMs = min(currentFrameLatencyMs, minFrameMs);
              totalDetectorMs += currentDetectorLatencyMs;
              maxDetectorMs = max(currentDetectorLatencyMs, maxDetectorMs);
              minDetectorMs = min(currentDetectorLatencyMs, minDetectorMs);

              // Only log inference info once per second. When frameProcessedInOneSecondInterval is
              // equal to 1, it means this is the first frame processed during the current second.
              if (frameProcessedInOneSecondInterval == 1) {
                Log.d(TAG, "Num of Runs: " + numRuns);
                Log.d(
                    TAG,
                    "Frame latency: max="
                        + maxFrameMs
                        + ", min="
                        + minFrameMs
                        + ", avg="
                        + totalFrameMs / numRuns);
                Log.d(
                    TAG,
                    "Detector latency: max="
                        + maxDetectorMs
                        + ", min="
                        + minDetectorMs
                        + ", avg="
                        + totalDetectorMs / numRuns);
                MemoryInfo mi = new MemoryInfo();
                activityManager.getMemoryInfo(mi);
                long availableMegs = mi.availMem / 0x100000L;
                Log.d(TAG, "Memory available in system: " + availableMegs + " MB");
                temperatureMonitor.logTemperature();
              }

              graphicOverlay.clear();
              if (originalCameraImage != null) {
                graphicOverlay.add(new CameraImageGraphic(graphicOverlay, originalCameraImage));
              }
              VisionProcessorBase.this.onSuccess(results, graphicOverlay);
              graphicOverlay.postInvalidate();
            })
        .addOnFailureListener(
            executor,
            e -> {
              graphicOverlay.clear();
              graphicOverlay.postInvalidate();
              String error = "Failed to process. Error: " + e.getLocalizedMessage();
              Toast.makeText(
                      graphicOverlay.getContext(),
                      error + "\nCause: " + e.getCause(),
                      Toast.LENGTH_SHORT)
                  .show();
              Log.d(TAG, error);
              e.printStackTrace();
              VisionProcessorBase.this.onFailure(e);
            });
  }

  @Override
  public void stop() {
    executor.shutdown();
    isShutdown = true;
    resetLatencyStats();
    fpsTimer.cancel();
    temperatureMonitor.stop();
  }

  private void resetLatencyStats() {
    numRuns = 0;
    totalFrameMs = 0;
    maxFrameMs = 0;
    minFrameMs = Long.MAX_VALUE;
    totalDetectorMs = 0;
    maxDetectorMs = 0;
    minDetectorMs = Long.MAX_VALUE;
  }

  protected abstract Task<T> detectInImage(InputImage image);

  protected Task<T> detectInImage(MlImage image) {
    return Tasks.forException(
        new MlKitException(
            "MlImage is currently not demonstrated for this feature",
            MlKitException.INVALID_ARGUMENT));
  }

  protected abstract void onSuccess(@NonNull T results, @NonNull GraphicOverlay graphicOverlay);

  protected abstract void onFailure(@NonNull Exception e);

  protected boolean isMlImageEnabled(Context context) {
    return false;
  }

  /**
   * Override to have a completed detection's result discarded rather than applied (see the check
   * in {@link #setUpListener}) - e.g. while a subclass is relying on the graphics it already
   * produced staying exactly as they are.
   */
  protected boolean isPaused() {
    return false;
  }

  /**
   * Returns the raw camera frame backing the detection results currently being handled in {@link
   * #onSuccess}, for cheap per-pixel color sampling (e.g. within a detected object's bounding
   * box) - or {@code null} when this frame didn't come from the CameraX {@link ImageProxy} path,
   * or wasn't available. Only valid for the duration of the current {@link #onSuccess} call.
   */
  @Nullable
  protected Image getLatestMediaImageForColorSampling() {
    return latestMediaImageForColorSampling;
  }

  /** The rotation to apply to {@link #getLatestMediaImageForColorSampling()}'s raw pixels. */
  protected int getLatestMediaImageRotationDegrees() {
    return latestMediaImageRotationDegrees;
  }
}
