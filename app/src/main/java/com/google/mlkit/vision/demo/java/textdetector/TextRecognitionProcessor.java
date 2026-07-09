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

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.java.VisionProcessorBase;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Processor for the text detector demo. */
public class TextRecognitionProcessor extends VisionProcessorBase<Text> {

  private static final String TAG = "TextRecProcessor";
  private static final long SPEAK_COOLDOWN_MS = 2000L;

  private final TextRecognizer textRecognizer;
  private final TextToSpeech textToSpeech;
  private final Context context;

  private volatile boolean textToSpeechReady;
  private long lastSpokenAtMs;
  private String lastSpokenText;
  private String lastFrameText = "";
  private int stabilityCount = 0;

  public TextRecognitionProcessor(Context context) {
    super(context);
    this.context = context.getApplicationContext();
    textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
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
    textRecognizer.close();
    textToSpeech.stop();
    textToSpeech.shutdown();
  }

  @Override
  protected Task<Text> detectInImage(InputImage image) {
    return textRecognizer.process(image);
  }

  @Override
  protected void onSuccess(@NonNull Text text, @NonNull GraphicOverlay graphicOverlay) {
    for (Text.TextBlock block : text.getTextBlocks()) {
      graphicOverlay.add(new TextGraphic(graphicOverlay, block));
    }
    speakDetectedText(text);
  }

  @Override
  protected void onFailure(@NonNull Exception e) {
    Log.w(TAG, "Text detection failed." + e);
  }

  private void speakDetectedText(Text text) {
    if (!textToSpeechReady || text.getTextBlocks().isEmpty()) {
      return;
    }

    if (textToSpeech.isSpeaking()) {
      return;
    }

    applySpeechSettings();

    List<Text.TextBlock> blocks = new ArrayList<>(text.getTextBlocks());
    // Sort blocks by vertical position, then horizontal to read in a natural order
    Collections.sort(
        blocks,
        (o1, o2) -> {
          Rect r1 = o1.getBoundingBox();
          Rect r2 = o2.getBoundingBox();
          if (r1 == null || r2 == null) {
            return 0;
          }
          int verticalDiff = r1.top - r2.top;
          if (Math.abs(verticalDiff) < 30) {
            return r1.left - r2.left;
          }
          return verticalDiff;
        });

    StringBuilder builder = new StringBuilder();
    for (Text.TextBlock block : blocks) {
      String blockText = block.getText().trim();
      Rect rect = block.getBoundingBox();
      // Filter out very short strings which are often noise
      // Also filter by bounding box size to avoid reading distant noise
      if (blockText.length() < 3 || rect == null || rect.width() < 20 || rect.height() < 20) {
        continue;
      }
      builder.append(blockText).append(". ");
    }

    String spokenText = builder.toString().trim();
    if (spokenText.isEmpty()) {
      return;
    }

    // Stability check: only speak if the text is consistent for a few frames
    if (spokenText.equals(lastFrameText)) {
      stabilityCount++;
    } else {
      lastFrameText = spokenText;
      stabilityCount = 0;
      return;
    }

    if (stabilityCount < 3) {
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
