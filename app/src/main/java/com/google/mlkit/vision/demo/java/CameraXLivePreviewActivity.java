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

import android.content.Intent;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory;
import com.google.android.gms.common.annotation.KeepName;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.LocalModel;
import com.google.mlkit.vision.demo.CameraPermission;
import com.google.mlkit.vision.demo.CameraXViewModel;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.MapsNavigator;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.VisionImageProcessor;
import com.google.mlkit.vision.demo.VoiceCommand;
import com.google.mlkit.vision.demo.VoiceCommandManager;
import com.google.mlkit.vision.demo.java.objectdetector.ObjectDetectorProcessor;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.demo.preference.SettingsActivity;
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions;
import java.util.ArrayList;
import java.util.List;

/** Live preview demo app for ML Kit APIs using CameraX. */
@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
public final class CameraXLivePreviewActivity extends LocaleAwareActivity
    implements OnItemSelectedListener, CompoundButton.OnCheckedChangeListener {
  private static final String TAG = "CameraXLivePreview";

  // TEMPORARY diagnostic logging for the tap-to-announce reliability issue - filter Logcat by
  // this exact tag. Remove once the root cause is confirmed and fixed.
  private static final String TAP_DEBUG_TAG = "PolarisTapDebug";

  private static final String OBJECT_DETECTION_CUSTOM = "Custom Object Detection";

  private static final String STATE_SELECTED_MODEL = "selected_model";
  private static final int REQUEST_LOCATION_PERMISSION = 100;
  private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
  private static final int REQUEST_CAMERA_PERMISSION = 300;

  private PreviewView previewView;
  private GraphicOverlay graphicOverlay;
  private ImageView muteButton;
  private ImageView voiceCommandMicButton;
  private ImageView crosshairToggleButton;
  private TextView ttsLanguageToggleButton;
  private ImageView colorModeToggleButton;
  private VoiceCommandManager voiceCommandManager;
  private ScaleGestureDetector pinchZoomGestureDetector;
  // Handles tap-to-announce. GestureDetector (not a hand-rolled ACTION_DOWN/ACTION_UP + boolean
  // flag) is what correctly refuses to fire onSingleTapUp() when a second pointer was involved
  // in the touch sequence - that disambiguation is implemented in the framework's own tracked
  // state, not something we have to get right ourselves.
  private GestureDetector tapGestureDetector;

  @Nullable private ProcessCameraProvider cameraProvider;
  @Nullable private Camera camera;
  @Nullable private Preview previewUseCase;
  @Nullable private ImageAnalysis analysisUseCase;
  @Nullable private VisionImageProcessor imageProcessor;
  private boolean needUpdateGraphicOverlayImageSourceInfo;

  private String selectedModel = OBJECT_DETECTION_CUSTOM;
  private int lensFacing = CameraSelector.LENS_FACING_BACK;
  private CameraSelector cameraSelector;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.d(TAG, "onCreate");

    if (savedInstanceState != null) {
      selectedModel = savedInstanceState.getString(STATE_SELECTED_MODEL, OBJECT_DETECTION_CUSTOM);
    }
    cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

    if (!CameraPermission.isGranted(this)) {
      CameraPermission.request(this, REQUEST_CAMERA_PERMISSION);
    }

    setContentView(R.layout.activity_vision_camerax_live_preview);
    previewView = findViewById(R.id.preview_view);
    if (previewView == null) {
      Log.d(TAG, "previewView is null");
    }
    graphicOverlay = findViewById(R.id.graphic_overlay);
    if (graphicOverlay == null) {
      Log.d(TAG, "graphicOverlay is null");
    }

    pinchZoomGestureDetector =
        new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          @Override
          public boolean onScaleBegin(ScaleGestureDetector detector) {
            Log.d(TAP_DEBUG_TAG, "(2) gesture classified as PINCH via onScaleBegin()");
            return true;
          }

          @Override
          public boolean onScale(ScaleGestureDetector detector) {
            adjustZoomBy(detector.getScaleFactor());
            return true;
          }
        });
    // "Quick scale" (a single-finger double-tap-then-drag) is enabled by default and needs no
    // second finger at all - left on, an ordinary double-tap (e.g. two quick taps to announce
    // twice) can spuriously trigger onScaleBegin(). Only a genuine two-finger pinch should ever
    // engage zoom here.
    pinchZoomGestureDetector.setQuickScaleEnabled(false);

    // onSingleTapUp() is GestureDetector's own tracked state machine for "this touch sequence
    // was a single-pointer tap" - it fires immediately (no double-tap wait, unlike
    // onSingleTapConfirmed()) and, crucially, does NOT fire if a second pointer was ever
    // involved, since that disambiguation lives in GestureDetector's own internal MotionEvent
    // tracking rather than a boolean flag we'd have to maintain (and have twice gotten wrong)
    // ourselves.
    tapGestureDetector =
        new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
          @Override
          public boolean onSingleTapUp(MotionEvent e) {
            Log.d(TAP_DEBUG_TAG, "(2) gesture classified as TAP via onSingleTapUp()");
            if (imageProcessor instanceof ObjectDetectorProcessor) {
              Log.d(TAP_DEBUG_TAG, "(3) calling announceCurrentDetection()");
              ((ObjectDetectorProcessor) imageProcessor).announceCurrentDetection(graphicOverlay);
            } else {
              Log.d(
                  TAP_DEBUG_TAG,
                  "(4) announce SKIPPED - imageProcessor is not an ObjectDetectorProcessor (was: "
                      + (imageProcessor == null
                          ? "null"
                          : imageProcessor.getClass().getSimpleName())
                      + ")");
            }
            return true;
          }
        });
    previewView.setOnTouchListener(this::onPreviewTouch);

    findViewById(R.id.detection_back_button).setOnClickListener(v -> finish());
    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.detection_header_title),
        ContextCompat.getColor(this, R.color.polaris_menu_accent),
        ContextCompat.getColor(this, R.color.polaris_blue_deep));

    Spinner spinner = findViewById(R.id.spinner);
    List<String> options = new ArrayList<>();
    options.add(OBJECT_DETECTION_CUSTOM);

    // Creating adapter for spinner
    ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, R.layout.spinner_style, options);
    // Drop down layout style - list view with radio button
    dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    // attaching data adapter to spinner
    spinner.setAdapter(dataAdapter);
    spinner.setOnItemSelectedListener(this);

    ToggleButton facingSwitch = findViewById(R.id.facing_switch);
    facingSwitch.setOnCheckedChangeListener(this);

    new ViewModelProvider(this, AndroidViewModelFactory.getInstance(getApplication()))
        .get(CameraXViewModel.class)
        .getProcessCameraProvider()
        .observe(
            this,
            provider -> {
              cameraProvider = provider;
              bindAllCameraUseCases();
            });

    ImageView settingsButton = findViewById(R.id.settings_button);
    settingsButton.setOnClickListener(
        v -> {
          Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
          intent.putExtra(
              SettingsActivity.EXTRA_LAUNCH_SOURCE,
              SettingsActivity.LaunchSource.CAMERAX_LIVE_PREVIEW);
          startActivity(intent);
        });

    muteButton = findViewById(R.id.mute_button);
    updateMuteButtonIcon();
    muteButton.setOnClickListener(
        v -> {
          boolean muted = !PreferenceUtils.isTextToSpeechMuted(this);
          PreferenceUtils.setTextToSpeechMuted(this, muted);
          updateMuteButtonIcon();
        });

    ImageView stopReadingButton = findViewById(R.id.stop_reading_button);
    stopReadingButton.setOnClickListener(
        v -> {
          if (imageProcessor instanceof ObjectDetectorProcessor) {
            ((ObjectDetectorProcessor) imageProcessor).stopSpeaking();
          }
        });

    voiceCommandMicButton = findViewById(R.id.voice_command_mic_button);
    voiceCommandManager = new VoiceCommandManager(this, new DetectionVoiceCommandListener());
    voiceCommandMicButton.setOnClickListener(v -> onVoiceCommandMicClicked());

    crosshairToggleButton = findViewById(R.id.crosshair_toggle_button);
    updateCrosshairToggleIcon();
    crosshairToggleButton.setOnClickListener(
        v -> {
          boolean enabled = !PreferenceUtils.isCrosshairModeEnabled(this);
          PreferenceUtils.setCrosshairModeEnabled(this, enabled);
          updateCrosshairToggleIcon();
        });

    ttsLanguageToggleButton = findViewById(R.id.tts_language_toggle_button);
    updateTtsLanguageToggle();
    ttsLanguageToggleButton.setOnClickListener(
        v -> {
          boolean tagalogSelected =
              PreferenceUtils.LANGUAGE_TAGALOG.equals(
                  PreferenceUtils.getTextToSpeechOutputLanguage(this));
          PreferenceUtils.setTextToSpeechOutputLanguage(
              this,
              tagalogSelected ? PreferenceUtils.LANGUAGE_ENGLISH : PreferenceUtils.LANGUAGE_TAGALOG);
          updateTtsLanguageToggle();
        });

    colorModeToggleButton = findViewById(R.id.color_mode_toggle_button);
    updateColorModeToggleIcon();
    colorModeToggleButton.setOnClickListener(
        v -> {
          boolean enabled = !PreferenceUtils.isColorModeEnabled(this);
          PreferenceUtils.setColorModeEnabled(this, enabled);
          updateColorModeToggleIcon();
        });
  }

  private void updateCrosshairToggleIcon() {
    boolean enabled = PreferenceUtils.isCrosshairModeEnabled(this);
    crosshairToggleButton.setColorFilter(
        enabled ? ContextCompat.getColor(this, R.color.polaris_menu_accent) : 0);
    crosshairToggleButton.setContentDescription(
        getString(
            enabled ? R.string.crosshair_mode_on_desc : R.string.crosshair_mode_off_desc));
  }

  private void updateTtsLanguageToggle() {
    boolean tagalogSelected =
        PreferenceUtils.LANGUAGE_TAGALOG.equals(PreferenceUtils.getTextToSpeechOutputLanguage(this));
    ttsLanguageToggleButton.setText(
        tagalogSelected
            ? R.string.tts_language_toggle_label_tagalog
            : R.string.tts_language_toggle_label_english);
    ttsLanguageToggleButton.setTextColor(
        tagalogSelected
            ? ContextCompat.getColor(this, R.color.polaris_menu_accent)
            : ContextCompat.getColor(this, android.R.color.white));
    ttsLanguageToggleButton.setContentDescription(
        getString(
            tagalogSelected
                ? R.string.tts_output_language_tagalog_desc
                : R.string.tts_output_language_english_desc));
  }

  private void updateColorModeToggleIcon() {
    boolean enabled = PreferenceUtils.isColorModeEnabled(this);
    colorModeToggleButton.setColorFilter(
        enabled ? ContextCompat.getColor(this, R.color.polaris_menu_accent) : 0);
    colorModeToggleButton.setContentDescription(
        getString(enabled ? R.string.color_mode_on_desc : R.string.color_mode_off_desc));
  }

  /**
   * Applies one pinch-to-zoom gesture step: multiplies the current zoom ratio by {@code
   * scaleFactor} (from {@link ScaleGestureDetector}, >1 for pinch-out/zoom-in, <1 for
   * pinch-in/zoom-out) and clamps to the camera's own hardware-supported range before applying,
   * so a pinch can never request a ratio the device doesn't actually support.
   */
  private void adjustZoomBy(float scaleFactor) {
    if (camera == null) {
      return;
    }
    ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
    if (zoomState == null) {
      return;
    }
    float requestedRatio = zoomState.getZoomRatio() * scaleFactor;
    float clampedRatio =
        Math.max(zoomState.getMinZoomRatio(), Math.min(zoomState.getMaxZoomRatio(), requestedRatio));
    camera.getCameraControl().setZoomRatio(clampedRatio);
  }

  /**
   * Handles every touch on the camera preview: feeds both gesture detectors unconditionally, on
   * every event, regardless of what the other one does. Neither detector's return value gates
   * the other - each is Android's own tracked state machine and independently decides whether
   * and when to fire its own callback (onScaleBegin()/onScale() for a pinch,
   * onSingleTapUp() for a tap), so there's no hand-rolled classification logic here to get wrong.
   */
  private boolean onPreviewTouch(View view, MotionEvent event) {
    // (1) Raw touch event received, before any gesture classification.
    Log.d(
        TAP_DEBUG_TAG,
        "(1) raw touch event: action="
            + MotionEvent.actionToString(event.getAction())
            + " pointerCount="
            + event.getPointerCount());

    pinchZoomGestureDetector.onTouchEvent(event);
    tapGestureDetector.onTouchEvent(event);
    return true;
  }

  private void onVoiceCommandMicClicked() {
    if (voiceCommandManager.isListening()) {
      voiceCommandManager.stopListening();
      return;
    }
    if (!VoiceCommandManager.hasRecordAudioPermission(this)) {
      VoiceCommandManager.requestRecordAudioPermission(this, REQUEST_RECORD_AUDIO_PERMISSION);
      return;
    }
    voiceCommandManager.startListening();
  }

  private void updateVoiceListeningUi(boolean listening) {
    voiceCommandMicButton.setColorFilter(
        listening ? ContextCompat.getColor(this, R.color.polaris_menu_accent) : 0);
    voiceCommandMicButton.setContentDescription(
        getString(
            listening ? R.string.voice_command_mic_listening_desc : R.string.voice_command_mic_desc));
  }

  private void handleVoiceCommand(VoiceCommand command) {
    switch (command) {
      case OPEN_OBJECT_DETECTION:
        // Already here.
        break;
      case OPEN_TEXT_TO_SPEECH:
        startActivity(new Intent(this, TextToSpeechLivePreviewActivity.class));
        break;
      case OPEN_NAVIGATION:
        if (MapsNavigator.hasLocationPermission(this)) {
          MapsNavigator.openMapsAtCurrentLocation(this);
        } else {
          MapsNavigator.requestLocationPermission(this, REQUEST_LOCATION_PERMISSION);
        }
        break;
      case GO_HOME:
        goHome();
        break;
      case REPEAT:
        if (imageProcessor instanceof ObjectDetectorProcessor) {
          ((ObjectDetectorProcessor) imageProcessor).repeatLastSpoken();
        }
        break;
      case STOP:
        if (imageProcessor instanceof ObjectDetectorProcessor) {
          ((ObjectDetectorProcessor) imageProcessor).stopSpeaking();
        }
        break;
    }
  }

  /** Returns to the home screen, clearing anything else on top of it in the back stack. */
  private void goHome() {
    Intent intent = new Intent(this, ChooserActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    startActivity(intent);
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_CAMERA_PERMISSION) {
      if (CameraPermission.isGranted(this)) {
        bindAllCameraUseCases();
      } else {
        Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
      }
      return;
    }
    if (requestCode == REQUEST_LOCATION_PERMISSION) {
      MapsNavigator.openMapsAtCurrentLocation(this);
      return;
    }
    if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
      if (VoiceCommandManager.hasRecordAudioPermission(this)) {
        voiceCommandManager.startListening();
      } else {
        Toast.makeText(this, R.string.voice_command_permission_denied, Toast.LENGTH_LONG).show();
      }
    }
  }

  private final class DetectionVoiceCommandListener implements VoiceCommandManager.Listener {
    @Override
    public void onListeningStateChanged(boolean listening) {
      updateVoiceListeningUi(listening);
    }

    @Override
    public void onCommandRecognized(VoiceCommand command) {
      handleVoiceCommand(command);
    }

    @Override
    public void onCommandNotRecognized(String heardText) {
      if (imageProcessor instanceof ObjectDetectorProcessor) {
        ((ObjectDetectorProcessor) imageProcessor)
            .speakFeedback(getString(R.string.voice_command_not_recognized));
      }
    }

    @Override
    public void onNoSpeechDetected() {
      // Timed out with no speech; the mic simply stops listening so the user can tap again.
    }

    @Override
    public void onRecognitionError(String message) {
      Toast.makeText(CameraXLivePreviewActivity.this, message, Toast.LENGTH_LONG).show();
    }
  }

  private void updateMuteButtonIcon() {
    boolean muted = PreferenceUtils.isTextToSpeechMuted(this);
    muteButton.setImageResource(
        muted ? R.drawable.ic_volume_off_white_24dp : R.drawable.ic_volume_up_white_24dp);
    muteButton.setContentDescription(
        getString(muted ? R.string.menu_item_unmute_tts : R.string.menu_item_mute_tts));
  }

  @Override
  protected void onSaveInstanceState(@NonNull Bundle bundle) {
    super.onSaveInstanceState(bundle);
    bundle.putString(STATE_SELECTED_MODEL, selectedModel);
  }

  @Override
  public synchronized void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
    // An item was selected. You can retrieve the selected item using
    // parent.getItemAtPosition(pos)
    selectedModel = parent.getItemAtPosition(pos).toString();
    Log.d(TAG, "Selected model: " + selectedModel);
    bindAnalysisUseCase();
  }

  @Override
  public void onNothingSelected(AdapterView<?> parent) {
    // Do nothing.
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
    if (cameraProvider == null) {
      return;
    }
    int newLensFacing =
        lensFacing == CameraSelector.LENS_FACING_FRONT
            ? CameraSelector.LENS_FACING_BACK
            : CameraSelector.LENS_FACING_FRONT;
    CameraSelector newCameraSelector =
        new CameraSelector.Builder().requireLensFacing(newLensFacing).build();
    try {
      if (cameraProvider.hasCamera(newCameraSelector)) {
        Log.d(TAG, "Set facing to " + newLensFacing);
        lensFacing = newLensFacing;
        cameraSelector = newCameraSelector;
        bindAllCameraUseCases();
        return;
      }
    } catch (CameraInfoUnavailableException e) {
      // Falls through
    }
    Toast.makeText(
            getApplicationContext(),
            "This device does not have lens with facing: " + newLensFacing,
            Toast.LENGTH_SHORT)
        .show();
  }

  @Override
  public void onResume() {
    super.onResume();
    bindAllCameraUseCases();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (imageProcessor != null) {
      imageProcessor.stop();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (imageProcessor != null) {
      imageProcessor.stop();
    }
    voiceCommandManager.destroy();
  }

  private void bindAllCameraUseCases() {
    if (cameraProvider != null) {
      // As required by CameraX API, unbinds all use cases before trying to re-bind any of them.
      cameraProvider.unbindAll();
      bindPreviewUseCase();
      bindAnalysisUseCase();
    }
  }

  private void bindPreviewUseCase() {
    if (!CameraPermission.isGranted(this)) {
      return;
    }
    if (!PreferenceUtils.isCameraLiveViewportEnabled(this)) {
      return;
    }
    if (cameraProvider == null) {
      return;
    }
    if (previewUseCase != null) {
      cameraProvider.unbind(previewUseCase);
    }

    Preview.Builder builder = new Preview.Builder();
    Size targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing);
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution);
    }
    previewUseCase = builder.build();
    previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
    camera =
        cameraProvider.bindToLifecycle(/* lifecycleOwner= */ this, cameraSelector, previewUseCase);
  }

  private void bindAnalysisUseCase() {
    if (!CameraPermission.isGranted(this)) {
      return;
    }
    if (cameraProvider == null) {
      return;
    }
    if (analysisUseCase != null) {
      cameraProvider.unbind(analysisUseCase);
    }
    if (imageProcessor != null) {
      imageProcessor.stop();
    }

    try {
      switch (selectedModel) {
        case OBJECT_DETECTION_CUSTOM:
          Log.i(TAG, "Using Custom Object Detector Processor");
          LocalModel localModel =
              new LocalModel.Builder()
                  .setAssetFilePath("custom_models/object_labeler.tflite")
                  .build();
          CustomObjectDetectorOptions customObjectDetectorOptions =
              PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(this, localModel);
          imageProcessor = new ObjectDetectorProcessor(this, customObjectDetectorOptions);
          break;
        default:
          throw new IllegalStateException("Invalid model name");
      }
    } catch (Exception e) {
      Log.e(TAG, "Can not create image processor: " + selectedModel, e);
      Toast.makeText(
              getApplicationContext(),
              "Can not create image processor: " + e.getLocalizedMessage(),
              Toast.LENGTH_LONG)
          .show();
      return;
    }

    ImageAnalysis.Builder builder = new ImageAnalysis.Builder();
    Size targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing);
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution);
    }
    analysisUseCase = builder.build();

    needUpdateGraphicOverlayImageSourceInfo = true;
    analysisUseCase.setAnalyzer(
        // imageProcessor.processImageProxy will use another thread to run the detection underneath,
        // thus we can just runs the analyzer itself on main thread.
        ContextCompat.getMainExecutor(this),
        imageProxy -> {
          if (needUpdateGraphicOverlayImageSourceInfo) {
            boolean isImageFlipped = lensFacing == CameraSelector.LENS_FACING_FRONT;
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            if (rotationDegrees == 0 || rotationDegrees == 180) {
              graphicOverlay.setImageSourceInfo(
                  imageProxy.getWidth(), imageProxy.getHeight(), isImageFlipped);
            } else {
              graphicOverlay.setImageSourceInfo(
                  imageProxy.getHeight(), imageProxy.getWidth(), isImageFlipped);
            }
            needUpdateGraphicOverlayImageSourceInfo = false;
          }
          try {
            imageProcessor.processImageProxy(imageProxy, graphicOverlay);
          } catch (MlKitException e) {
            Log.e(TAG, "Failed to process image. Error: " + e.getLocalizedMessage());
            Toast.makeText(getApplicationContext(), e.getLocalizedMessage(), Toast.LENGTH_SHORT)
                .show();
          }
        });

    cameraProvider.bindToLifecycle(/* lifecycleOwner= */ this, cameraSelector, analysisUseCase);
  }
}
