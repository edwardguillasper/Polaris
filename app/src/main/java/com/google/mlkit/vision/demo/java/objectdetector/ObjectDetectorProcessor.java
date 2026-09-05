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
import android.graphics.Rect;
import android.media.Image;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.demo.CrosshairGraphic;
import com.google.mlkit.vision.demo.FrameColorSampler;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.VoiceCommandState;
import com.google.mlkit.vision.demo.data.ActivityLogRepository;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.demo.java.VisionProcessorBase;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A processor to run object detector. */
public class ObjectDetectorProcessor extends VisionProcessorBase<List<DetectedObject>> {

  private static final String TAG = "ObjectDetectorProcessor";

  // TEMPORARY diagnostic logging for the tap-to-announce reliability issue - filter Logcat by
  // this exact tag. Remove once the root cause is confirmed and fixed.
  private static final String TAP_DEBUG_TAG = "PolarisTapDebug";

  // A "session" is a burst of frames that keep detecting objects. Rather than writing an activity
  // log entry for every analyzed frame (multiple per second), we coalesce them into one entry per
  // this interval.
  private static final long LOG_SESSION_COOLDOWN_MS = 3000L;

  private final Context context;
  private final ObjectDetector detector;
  private final TextToSpeech textToSpeech;

  private volatile boolean textToSpeechReady;
  private String lastSpokenText;
  private long lastLoggedAtMs;

  // Detection keeps running continuously in the background regardless of whether anything gets
  // spoken, so a tap can announce the latest frame's results immediately with no extra lag.
  private volatile List<DetectedObject> latestResults = new ArrayList<>();

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

  /** Immediately interrupts any speech currently in progress, without affecting mute state. */
  public void stopSpeaking() {
    textToSpeech.stop();
  }

  /** Re-speaks the last detected object label, for the "Repeat"/"Say Again" voice command. */
  public void repeatLastSpoken() {
    speakFeedback(lastSpokenText != null ? lastSpokenText : "Nothing detected yet");
  }

  /** Speaks an arbitrary feedback message, e.g. for an unrecognized voice command. */
  public void speakFeedback(String text) {
    if (!textToSpeechReady || PreferenceUtils.isTextToSpeechMuted(context)) {
      return;
    }
    applySpeechSettings();
    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, TAG + "_feedback");
  }

  @Override
  protected Task<List<DetectedObject>> detectInImage(InputImage image) {
    return detector.process(image);
  }

  @Override
  protected void onSuccess(
      @NonNull List<DetectedObject> results, @NonNull GraphicOverlay graphicOverlay) {
    latestResults = results;

    for (DetectedObject object : results) {
      graphicOverlay.add(new ObjectGraphic(graphicOverlay, object));
    }

    boolean crosshairModeEnabled = PreferenceUtils.isCrosshairModeEnabled(context);
    boolean colorModeEnabled = PreferenceUtils.isColorModeEnabled(context);
    if (crosshairModeEnabled || colorModeEnabled) {
      // Color mode samples the same crosshair/priority-area region, so show the reticle to aim
      // with even if crosshair mode itself (object-label filtering) is off.
      graphicOverlay.add(new CrosshairGraphic(graphicOverlay, CrosshairGraphic.Shape.SQUARE));
    }
    if (colorModeEnabled) {
      // Shows what color would currently be announced - a live visual preview, independent of
      // the tap-gated announcement itself.
      addColorLabelGraphic(graphicOverlay);
    }

    // Detection (and the graphics above) keeps running continuously in the background - only
    // the spoken announcement itself is gated behind a tap, via announceCurrentDetection(), so
    // results are ready with no lag whenever the user asks.
    logDetectionSession(results);
  }

  private void addColorLabelGraphic(GraphicOverlay graphicOverlay) {
    Image mediaImage = getLatestMediaImageForColorSampling();
    if (mediaImage == null) {
      return;
    }
    String colorName =
        FrameColorSampler.sampleCrosshairColor(
            mediaImage, getLatestMediaImageRotationDegrees(), graphicOverlay);
    if (colorName == null) {
      return;
    }
    Rect crosshairRect =
        CrosshairGraphic.computeImageRect(graphicOverlay, CrosshairGraphic.Shape.SQUARE);
    if (crosshairRect == null) {
      return;
    }
    graphicOverlay.add(new ColorLabelGraphic(graphicOverlay, colorName, crosshairRect));
  }

  @Override
  protected void onFailure(@NonNull Exception e) {
    Log.e(TAG, "Object detection failed!", e);
  }

  /**
   * Announces whatever is currently in frame - respecting crosshair filtering and color mode
   * exactly as the previous continuous, per-frame announcements did - triggered by a tap instead
   * of automatically on every detection frame.
   */
  public void announceCurrentDetection(GraphicOverlay graphicOverlay) {
    boolean colorModeEnabled = PreferenceUtils.isColorModeEnabled(context);
    Log.d(
        TAP_DEBUG_TAG,
        "(3) announceCurrentDetection() entered - colorModeEnabled=" + colorModeEnabled);
    if (colorModeEnabled) {
      speakSampledColor(graphicOverlay);
    } else {
      List<DetectedObject> results = latestResults;
      boolean crosshairModeEnabled = PreferenceUtils.isCrosshairModeEnabled(context);
      List<DetectedObject> toSpeak =
          crosshairModeEnabled ? filterToCrosshairArea(results, graphicOverlay) : results;
      Log.d(
          TAP_DEBUG_TAG,
          "(3) object mode - latestResults.size()="
              + results.size()
              + " crosshairModeEnabled="
              + crosshairModeEnabled
              + " -> "
              + toSpeak.size()
              + " object(s) to speak");
      speakDetectedObjects(toSpeak);
    }
  }

  /**
   * Returns only the objects whose bounding box overlaps the crosshair reticle's area, so
   * crosshair mode speaks about what the user is actually pointing at rather than everything
   * ML Kit found in frame. Falls back to the unfiltered list on the rare frame where the
   * overlay doesn't know the image size yet.
   */
  private List<DetectedObject> filterToCrosshairArea(
      List<DetectedObject> results, GraphicOverlay graphicOverlay) {
    Rect crosshairRect = CrosshairGraphic.computeImageRect(graphicOverlay, CrosshairGraphic.Shape.SQUARE);
    if (crosshairRect == null) {
      return results;
    }
    List<DetectedObject> inCrosshair = new ArrayList<>();
    for (DetectedObject object : results) {
      if (Rect.intersects(crosshairRect, object.getBoundingBox())) {
        inCrosshair.add(object);
      }
    }
    return inCrosshair;
  }

  /**
   * Speaks {@code results} once, immediately - called only from a tap (see {@link
   * #announceCurrentDetection}), so there's no continuous-streaming spam to guard against:
   * unlike the old per-frame version, this doesn't check {@code isSpeaking()} or debounce
   * repeating the same text, since {@code QUEUE_FLUSH} already gives a deliberate re-tap what it
   * asks for - hearing the current detection again, immediately.
   */
  private void speakDetectedObjects(List<DetectedObject> results) {
    if (!textToSpeechReady) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - textToSpeech not ready yet");
      return;
    }
    if (results.isEmpty()) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - no object currently detected (results empty)");
      return;
    }

    if (PreferenceUtils.isTextToSpeechMuted(context)) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - TTS is muted");
      return;
    }

    applySpeechSettings();

    LinkedHashSet<String> labels = collectLabels(results);
    String spokenText = buildSpokenText(labels);
    if (spokenText.isEmpty()) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - built spoken text was empty");
      return;
    }

    lastSpokenText = spokenText;
    Log.d(TAP_DEBUG_TAG, "(3) speaking now: \"" + spokenText + "\"");
    textToSpeech.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, TAG);
    VoiceCommandState.recordDetectedObjectSpeech(spokenText);
  }

  /**
   * Standalone color mode: samples the crosshair/priority-area region directly from the raw
   * camera frame and announces just the color name, independent of whatever (if anything) ML Kit
   * classified there. Tap-triggered like {@link #speakDetectedObjects}, for the same reason.
   */
  private void speakSampledColor(GraphicOverlay graphicOverlay) {
    if (!textToSpeechReady) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - textToSpeech not ready yet");
      return;
    }

    if (PreferenceUtils.isTextToSpeechMuted(context)) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - TTS is muted");
      return;
    }

    Image mediaImage = getLatestMediaImageForColorSampling();
    if (mediaImage == null) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - no camera frame available to sample color from");
      return;
    }

    String colorName =
        FrameColorSampler.sampleCrosshairColor(
            mediaImage, getLatestMediaImageRotationDegrees(), graphicOverlay);
    if (colorName == null) {
      Log.d(TAP_DEBUG_TAG, "(4) announce SKIPPED - color sampling returned null (see "
          + "FrameColorSampler logs for the specific reason)");
      return;
    }

    applySpeechSettings();

    lastSpokenText = colorName;
    Log.d(TAP_DEBUG_TAG, "(3) speaking now: \"" + colorName + "\"");
    textToSpeech.speak(colorName, TextToSpeech.QUEUE_FLUSH, null, TAG);
    VoiceCommandState.recordDetectedObjectSpeech(colorName);
  }

  private void logDetectionSession(List<DetectedObject> results) {
    if (results.isEmpty()) {
      return;
    }

    long now = SystemClock.elapsedRealtime();
    if (lastLoggedAtMs != 0 && now - lastLoggedAtMs < LOG_SESSION_COOLDOWN_MS) {
      return;
    }
    lastLoggedAtMs = now;

    LinkedHashSet<String> labels = collectLabels(results);
    String detail =
        labels.isEmpty()
            ? (results.size() == 1 ? "1 object detected" : results.size() + " objects detected")
            : "Detected: " + String.join(", ", labels);
    ActivityLogRepository.getInstance(context)
        .logActivity(ActivityLogRepository.TYPE_OBJECT_DETECTION, detail);
  }

  private LinkedHashSet<String> collectLabels(List<DetectedObject> results) {
    LinkedHashSet<String> labels = new LinkedHashSet<>();
    for (DetectedObject object : results) {
      if (object.getLabels().isEmpty()) {
        continue;
      }

      labels.add(object.getLabels().get(0).getText());
    }
    return labels;
  }

  private String buildSpokenText(LinkedHashSet<String> labels) {
    if (labels.isEmpty()) {
      return "Object detected";
    }

    return "Detected " + String.join(", ", labels);
  }

  private void applySpeechSettings() {
    if (!textToSpeechReady) {
      return;
    }

    Locale localeOverride = PreferenceUtils.getTextToSpeechLocaleOverride(context);
    if (localeOverride != null) {
      int availability = textToSpeech.isLanguageAvailable(localeOverride);
      Log.d(
          TAG,
          "TextToSpeech.isLanguageAvailable("
              + localeOverride
              + ") returned "
              + PreferenceUtils.describeTextToSpeechLanguageResult(availability));
      int languageResult =
          availability >= TextToSpeech.LANG_AVAILABLE
              ? textToSpeech.setLanguage(localeOverride)
              : availability;
      Log.d(
          TAG,
          "TextToSpeech.setLanguage("
              + localeOverride
              + ") returned "
              + PreferenceUtils.describeTextToSpeechLanguageResult(languageResult));
      if (languageResult == TextToSpeech.LANG_MISSING_DATA
          || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.w(TAG, "Voice data for " + localeOverride + " unavailable; using default TTS voice");
      }
    }

    textToSpeech.setPitch(PreferenceUtils.getTextToSpeechPitch(context));
    textToSpeech.setSpeechRate(PreferenceUtils.getTextToSpeechRate(context));
    Voice preferredVoice =
        findPreferredVoice(PreferenceUtils.getTextToSpeechVoicePreset(context), localeOverride);
    if (preferredVoice != null) {
      textToSpeech.setVoice(preferredVoice);
    }
  }

  private Voice findPreferredVoice(String voicePreset, @Nullable Locale localeOverride) {
    Set<Voice> voices = textToSpeech.getVoices();
    if (voices == null || voices.isEmpty()) {
      return null;
    }

    Iterable<Voice> searchVoices = voices;
    Voice localeFallback = null;
    if (localeOverride != null) {
      List<Voice> localeMatches = new ArrayList<>();
      for (Voice voice : voices) {
        if (voice.getLocale().getLanguage().equalsIgnoreCase(localeOverride.getLanguage())) {
          localeMatches.add(voice);
        }
      }
      if (!localeMatches.isEmpty()) {
        searchVoices = localeMatches;
        localeFallback = localeMatches.get(0);
      } else {
        Log.w(TAG, "No installed TTS voice matches locale " + localeOverride);
      }
    }

    String normalizedPreset = voicePreset.toLowerCase(Locale.US);
    Voice defaultVoice = textToSpeech.getDefaultVoice();
    if (normalizedPreset.equals("default")) {
      return localeFallback != null ? localeFallback : defaultVoice;
    }

    Voice fallbackVoice = localeFallback != null ? localeFallback : defaultVoice;
    for (Voice voice : searchVoices) {
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
