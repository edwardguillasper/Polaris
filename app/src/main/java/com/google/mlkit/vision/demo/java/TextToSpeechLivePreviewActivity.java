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

import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory;
import com.google.android.gms.common.annotation.KeepName;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.demo.CameraXViewModel;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.VisionImageProcessor;
import com.google.mlkit.vision.demo.data.ActivityLogRepository;
import com.google.mlkit.vision.demo.java.textdetector.TextElementGraphic;
import com.google.mlkit.vision.demo.java.textdetector.TextRecognitionProcessor;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Live preview demo that reads recognized text aloud, either word-by-word or all at once. */
@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
public final class TextToSpeechLivePreviewActivity extends LocaleAwareActivity
    implements CompoundButton.OnCheckedChangeListener,
        TextRecognitionProcessor.OnTextRecognizedListener {

  private static final String TAG = "TextToSpeechLivePreview";

  private PreviewView previewView;
  private GraphicOverlay graphicOverlay;
  private ImageView muteButton;

  @Nullable private ProcessCameraProvider cameraProvider;
  @Nullable private Camera camera;
  @Nullable private Preview previewUseCase;
  @Nullable private ImageAnalysis analysisUseCase;
  @Nullable private VisionImageProcessor imageProcessor;
  private boolean needUpdateGraphicOverlayImageSourceInfo;

  private int lensFacing = CameraSelector.LENS_FACING_BACK;
  private CameraSelector cameraSelector;

  private TextToSpeech textToSpeech;
  private volatile boolean textToSpeechReady;

  @Nullable private Text latestText;
  private List<TextElementGraphic> latestElementGraphics = new ArrayList<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.d(TAG, "onCreate");

    cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

    setContentView(R.layout.activity_text_to_speech_live_preview);
    previewView = findViewById(R.id.preview_view);
    if (previewView == null) {
      Log.d(TAG, "previewView is null");
    }
    graphicOverlay = findViewById(R.id.graphic_overlay);
    if (graphicOverlay == null) {
      Log.d(TAG, "graphicOverlay is null");
    }
    graphicOverlay.setOnTouchListener(this::onOverlayTouch);

    ToggleButton facingSwitch = findViewById(R.id.facing_switch);
    facingSwitch.setOnCheckedChangeListener(this);

    Button readAllButton = findViewById(R.id.read_all_button);
    readAllButton.setOnClickListener(v -> speakAllText());

    muteButton = findViewById(R.id.mute_button);
    updateMuteButtonIcon();
    muteButton.setOnClickListener(
        v -> {
          boolean muted = !PreferenceUtils.isTextToSpeechMuted(this);
          PreferenceUtils.setTextToSpeechMuted(this, muted);
          updateMuteButtonIcon();
        });

    ImageView stopReadingButton = findViewById(R.id.stop_reading_button);
    stopReadingButton.setOnClickListener(v -> textToSpeech.stop());

    textToSpeech =
        new TextToSpeech(
            this,
            status -> {
              if (status == TextToSpeech.SUCCESS) {
                applySpeechSettings();
                textToSpeechReady = true;
              } else {
                Log.e(TAG, "Text-to-speech initialization failed");
              }
            });

    new ViewModelProvider(this, AndroidViewModelFactory.getInstance(getApplication()))
        .get(CameraXViewModel.class)
        .getProcessCameraProvider()
        .observe(
            this,
            provider -> {
              cameraProvider = provider;
              bindAllCameraUseCases();
            });
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
    textToSpeech.stop();
    textToSpeech.shutdown();
  }

  private void updateMuteButtonIcon() {
    boolean muted = PreferenceUtils.isTextToSpeechMuted(this);
    muteButton.setImageResource(
        muted ? R.drawable.ic_volume_off_white_24dp : R.drawable.ic_volume_up_white_24dp);
    muteButton.setContentDescription(
        getString(muted ? R.string.menu_item_unmute_tts : R.string.menu_item_mute_tts));
  }

  @Override
  public void onTextRecognized(Text text, List<TextElementGraphic> elementGraphics) {
    latestText = text;
    latestElementGraphics = elementGraphics;
  }

  private boolean onOverlayTouch(View view, MotionEvent event) {
    if (event.getAction() != MotionEvent.ACTION_UP) {
      return true;
    }
    float x = event.getX();
    float y = event.getY();
    for (TextElementGraphic graphic : latestElementGraphics) {
      if (graphic.contains(x, y)) {
        speakWord(graphic.getText());
        break;
      }
    }
    return true;
  }

  private void speakWord(String word) {
    if (!textToSpeechReady || word == null || word.trim().isEmpty()) {
      return;
    }
    if (PreferenceUtils.isTextToSpeechMuted(this)) {
      return;
    }
    applySpeechSettings();
    textToSpeech.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word");
    ActivityLogRepository.getInstance(this)
        .logActivity(
            ActivityLogRepository.TYPE_TEXT_TO_SPEECH, "Word read aloud: \"" + word.trim() + "\"");
  }

  private void speakAllText() {
    Text text = latestText;
    if (!textToSpeechReady) {
      return;
    }
    if (text == null || text.getTextBlocks().isEmpty()) {
      Toast.makeText(this, R.string.text_to_speech_no_text_recognized, Toast.LENGTH_SHORT).show();
      return;
    }

    // Read in natural reading order: block by block, line by line.
    StringBuilder builder = new StringBuilder();
    for (Text.TextBlock block : text.getTextBlocks()) {
      for (Text.Line line : block.getLines()) {
        String lineText = line.getText().trim();
        if (!lineText.isEmpty()) {
          builder.append(lineText).append(". ");
        }
      }
    }

    String spokenText = builder.toString().trim();
    if (spokenText.isEmpty()) {
      Toast.makeText(this, R.string.text_to_speech_no_text_recognized, Toast.LENGTH_SHORT).show();
      return;
    }

    if (PreferenceUtils.isTextToSpeechMuted(this)) {
      return;
    }

    applySpeechSettings();
    textToSpeech.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "read_all");
    ActivityLogRepository.getInstance(this)
        .logActivity(ActivityLogRepository.TYPE_TEXT_TO_SPEECH, "Text scanned and read aloud");
  }

  private void applySpeechSettings() {
    textToSpeech.setPitch(PreferenceUtils.getTextToSpeechPitch(this));
    textToSpeech.setSpeechRate(PreferenceUtils.getTextToSpeechRate(this));
    Voice preferredVoice = findPreferredVoice(PreferenceUtils.getTextToSpeechVoicePreset(this));
    if (preferredVoice != null) {
      textToSpeech.setVoice(preferredVoice);
    }
  }

  private Voice findPreferredVoice(String voicePreset) {
    Set<Voice> voices = textToSpeech.getVoices();
    if (voices == null || voices.isEmpty()) {
      return null;
    }

    String normalizedPreset = voicePreset.toLowerCase(Locale.US);
    Voice defaultVoice = textToSpeech.getDefaultVoice();
    if (normalizedPreset.equals("default")) {
      return defaultVoice;
    }

    Voice fallbackVoice = defaultVoice;
    for (Voice voice : voices) {
      String voiceName = voice.getName().toLowerCase(Locale.US);
      if (voiceName.contains(normalizedPreset)) {
        return voice;
      }

      if (fallbackVoice == null && voice.getLocale().equals(Locale.getDefault())) {
        fallbackVoice = voice;
      }
    }

    return fallbackVoice;
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
    if (cameraProvider == null) {
      return;
    }
    if (analysisUseCase != null) {
      cameraProvider.unbind(analysisUseCase);
    }
    if (imageProcessor != null) {
      imageProcessor.stop();
    }

    imageProcessor = new TextRecognitionProcessor(this, this);

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
