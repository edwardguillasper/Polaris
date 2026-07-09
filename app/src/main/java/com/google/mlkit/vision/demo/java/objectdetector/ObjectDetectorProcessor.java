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

import android.content.Context;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.demo.java.VisionProcessorBase;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A processor to run object detector. */
public class ObjectDetectorProcessor extends VisionProcessorBase<List<DetectedObject>> {

  private static final String TAG = "ObjectDetectorProcessor";
  private static final long SPEAK_COOLDOWN_MS = 2000L;

  private final Context context;
  private final ObjectDetector detector;
  private final TextToSpeech textToSpeech;

  private volatile boolean textToSpeechReady;
  private long lastSpokenAtMs;
  private String lastSpokenText;

  public ObjectDetectorProcessor(Context context, ObjectDetectorOptionsBase options) {
    super(context);
    this.context = context.getApplicationContext();
    detector = ObjectDetection.getClient(options);
    textToSpeech =
        new TextToSpeech(
            this.context,
            status -> {
              if (status == TextToSpeech.SUCCESS) {
                applySpeechSettings();
                textToSpeechReady = true;
              } else {
                Log.e(TAG, "Text-to-speech initialization failed");
              }
            });
  }

  @Override
  public void stop() {
    super.stop();
    detector.close();
    textToSpeech.stop();
    textToSpeech.shutdown();
  }

  @Override
  protected Task<List<DetectedObject>> detectInImage(InputImage image) {
    return detector.process(image);
  }

  @Override
  protected void onSuccess(
      @NonNull List<DetectedObject> results, @NonNull GraphicOverlay graphicOverlay) {
    for (DetectedObject object : results) {
      graphicOverlay.add(new ObjectGraphic(graphicOverlay, object));
    }
    speakDetectedObjects(results);
  }

  @Override
  protected void onFailure(@NonNull Exception e) {
    Log.e(TAG, "Object detection failed!", e);
  }

  private void speakDetectedObjects(List<DetectedObject> results) {
    if (!textToSpeechReady || results.isEmpty()) {
      return;
    }

    if (textToSpeech.isSpeaking()) {
      return;
    }

    applySpeechSettings();

    String spokenText = buildSpokenText(results);
    if (spokenText.isEmpty()) {
      return;
    }

    long now = SystemClock.elapsedRealtime();
    if (spokenText.equals(lastSpokenText) && now - lastSpokenAtMs < SPEAK_COOLDOWN_MS) {
      return;
    }

    lastSpokenText = spokenText;
    lastSpokenAtMs = now;
    textToSpeech.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, TAG);
  }

  private String buildSpokenText(List<DetectedObject> results) {
    LinkedHashSet<String> labels = new LinkedHashSet<>();
    for (DetectedObject object : results) {
      if (object.getLabels().isEmpty()) {
        continue;
      }

      labels.add(object.getLabels().get(0).getText());
    }

    if (labels.isEmpty()) {
      return "Object detected";
    }

    return "Detected " + String.join(", ", labels);
  }

  private void applySpeechSettings() {
    textToSpeech.setPitch(PreferenceUtils.getTextToSpeechPitch(context));
    textToSpeech.setSpeechRate(PreferenceUtils.getTextToSpeechRate(context));
    Voice preferredVoice = findPreferredVoice(PreferenceUtils.getTextToSpeechVoicePreset(context));
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
}
