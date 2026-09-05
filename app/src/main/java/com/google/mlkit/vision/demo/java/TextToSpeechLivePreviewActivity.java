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
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
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
import com.google.mlkit.vision.demo.CameraPermission;
import com.google.mlkit.vision.demo.CameraXViewModel;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.MapsNavigator;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.VisionImageProcessor;
import com.google.mlkit.vision.demo.VoiceCommand;
import com.google.mlkit.vision.demo.VoiceCommandManager;
import com.google.mlkit.vision.demo.VoiceCommandState;
import com.google.mlkit.vision.demo.data.ActivityLogRepository;
import com.google.mlkit.vision.demo.java.textdetector.TextElementGraphic;
import com.google.mlkit.vision.demo.java.textdetector.TextRecognitionProcessor;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Live preview demo that reads recognized text aloud, either word-by-word or all at once. */
@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
public final class TextToSpeechLivePreviewActivity extends LocaleAwareActivity
    implements CompoundButton.OnCheckedChangeListener,
        TextRecognitionProcessor.OnTextRecognizedListener {

  private static final String TAG = "TextToSpeechLivePreview";
  private static final int REQUEST_LOCATION_PERMISSION = 100;
  private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
  private static final int REQUEST_CAMERA_PERMISSION = 300;

  private PreviewView previewView;
  private GraphicOverlay graphicOverlay;
  private ImageView muteButton;
  private ImageView voiceCommandMicButton;
  private ImageView crosshairToggleButton;
  private TextView ttsLanguageToggleButton;
  private TextView noTextDetectedMessage;
  private TextView holdCameraSteadyMessage;
  private VoiceCommandManager voiceCommandManager;
  private String lastSpokenText;
  private ScaleGestureDetector pinchZoomGestureDetector;
  // Handles word-tap-to-speak. GestureDetector's own onSingleTapUp() correctly refuses to fire
  // when a second pointer was involved in the touch sequence - that disambiguation lives in the
  // framework's tracked state, not a hand-rolled boolean flag.
  private GestureDetector tapGestureDetector;

  private long noTextDetectedSinceMs;

  @Nullable private ProcessCameraProvider cameraProvider;
  @Nullable private Camera camera;
  @Nullable private Preview previewUseCase;
  @Nullable private ImageAnalysis analysisUseCase;
  @Nullable private VisionImageProcessor imageProcessor;
  private boolean needUpdateGraphicOverlayImageSourceInfo;

  private int lensFacing = CameraSelector.LENS_FACING_BACK;
  private CameraSelector cameraSelector;

  // Single prefix for every kind of reading session (word tap, Read All) - they're both "one
  // utterance, tagged with a generation" under the hood, so onRangeStart/onDone/onError don't
  // need to know which UI action started it.
  private static final String READING_UTTERANCE_PREFIX = "reading_";

  // How long detection has to keep finding nothing before the "no text detected" banner shows -
  // avoids flickering it on/off during momentary gaps between successful detections.
  private static final long NO_TEXT_DETECTED_DELAY_MS = 1500L;

  private TextToSpeech textToSpeech;
  private volatile boolean textToSpeechReady;

  private List<TextElementGraphic> latestElementGraphics = new ArrayList<>();

  // The word(s) currently being read aloud, so exactly one highlight shows at a time instead of
  // every detected word being boxed simultaneously. `activeReadingWordStarts/Ends` are parallel
  // arrays giving each word's [start, end) character offset within the utterance currently
  // playing, used to move the highlight in sync with TextToSpeech's onRangeStart callback during
  // "Read All". `readingGeneration` tags each reading session so a stale callback from an
  // utterance that's since been superseded (e.g. by a new tap, or a manual Stop) is ignored
  // rather than corrupting the current one's state.
  private List<TextElementGraphic> activeReadingWords = Collections.emptyList();
  private int[] activeReadingWordStarts = new int[0];
  private int[] activeReadingWordEnds = new int[0];
  private int readingGeneration;

  // While true, newly analyzed camera frames are dropped instead of being handed to the text
  // recognizer, so the word graphics captured into activeReadingWords - and their on-screen
  // positions - stay valid for the whole read instead of being replaced mid-utterance by a new
  // detection frame. Continuous re-scanning during a read was tried and reverted (it introduced
  // its own bug where highlight boxes sometimes didn't show at all); holdCameraSteadyMessage
  // tells the user why the boxes won't track camera movement during a read, instead.
  private volatile boolean detectionPaused;

  // See applySpeechSettings(): caches what was actually applied to the TextToSpeech engine last
  // time, so repeated calls (one per utterance) skip the native setLanguage()/setVoice() calls -
  // and the voice-list scan in findPreferredVoice() - when nothing has changed since.
  private boolean speechSettingsApplied;
  @Nullable private Locale lastAppliedLocaleOverride;
  private String lastAppliedVoicePreset;
  private float lastAppliedPitch = Float.NaN;
  private float lastAppliedRate = Float.NaN;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.d(TAG, "onCreate");

    cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

    if (!CameraPermission.isGranted(this)) {
      CameraPermission.request(this, REQUEST_CAMERA_PERMISSION);
    }

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
    pinchZoomGestureDetector =
        new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
          @Override
          public boolean onScale(ScaleGestureDetector detector) {
            adjustZoomBy(detector.getScaleFactor());
            return true;
          }
        });
    // "Quick scale" (a single-finger double-tap-then-drag) is enabled by default and needs no
    // second finger at all. Only a genuine two-finger pinch should ever engage zoom here.
    pinchZoomGestureDetector.setQuickScaleEnabled(false);

    // onSingleTapUp() fires immediately for a confirmed single-pointer tap and does not fire if
    // a second pointer was ever involved in the touch sequence, so it coexists with
    // pinchZoomGestureDetector above without either needing to track the other's state.
    tapGestureDetector =
        new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
          @Override
          public boolean onSingleTapUp(MotionEvent e) {
            float x = e.getX();
            float y = e.getY();
            for (TextElementGraphic graphic : latestElementGraphics) {
              if (graphic.contains(x, y)) {
                speakWord(graphic);
                break;
              }
            }
            return true;
          }
        });
    noTextDetectedMessage = findViewById(R.id.no_text_detected_message);
    holdCameraSteadyMessage = findViewById(R.id.hold_steady_message);

    findViewById(R.id.ocr_back_button).setOnClickListener(v -> finish());
    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.ocr_header_title),
        ContextCompat.getColor(this, R.color.polaris_menu_accent),
        ContextCompat.getColor(this, R.color.polaris_blue_deep));

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
    stopReadingButton.setOnClickListener(
        v -> {
          textToSpeech.stop();
          // stop() isn't guaranteed to trigger onDone()/onError() for the utterance it
          // interrupts, so clear the reading session here too rather than relying solely on
          // the progress listener - otherwise a manual Stop could leave detection paused
          // forever.
          cancelReadingSession();
        });

    voiceCommandMicButton = findViewById(R.id.voice_command_mic_button);
    voiceCommandManager = new VoiceCommandManager(this, new OcrVoiceCommandListener());
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
    // Registered once at init rather than per-utterance: onRangeStart moves the highlight to
    // whichever word is currently being spoken during "Read All", and onDone/onError release the
    // detection pause once an utterance finishes (naturally or via error) so the camera resumes.
    textToSpeech.setOnUtteranceProgressListener(
        new UtteranceProgressListener() {
          @Override
          public void onStart(String utteranceId) {}

          @Override
          public void onRangeStart(String utteranceId, int start, int end, int frame) {
            Integer generation = parseReadingGeneration(READING_UTTERANCE_PREFIX, utteranceId);
            if (generation == null) {
              return;
            }
            runOnUiThread(
                () -> {
                  if (generation != readingGeneration) {
                    return; // Stale callback from a superseded utterance.
                  }
                  int index = findWordIndexForOffset(start);
                  if (index >= 0) {
                    setActiveWordIndex(index);
                  }
                });
          }

          @Override
          public void onDone(String utteranceId) {
            runOnUiThread(() -> finishReadingSessionIfCurrent(utteranceId));
          }

          @Override
          public void onError(String utteranceId) {
            runOnUiThread(() -> finishReadingSessionIfCurrent(utteranceId));
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
    voiceCommandManager.destroy();
    textToSpeech.stop();
    textToSpeech.shutdown();
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
        startActivity(new Intent(this, CameraXLivePreviewActivity.class));
        break;
      case OPEN_TEXT_TO_SPEECH:
        // Already here.
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
        repeatLastReadText();
        break;
      case STOP:
        textToSpeech.stop();
        cancelReadingSession();
        break;
    }
  }

  /** Returns to the home screen, clearing anything else on top of it in the back stack. */
  private void goHome() {
    Intent intent = new Intent(this, ChooserActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    startActivity(intent);
  }

  private void repeatLastReadText() {
    if (!textToSpeechReady || PreferenceUtils.isTextToSpeechMuted(this)) {
      return;
    }
    applySpeechSettings();
    String text = lastSpokenText != null ? lastSpokenText : getString(R.string.text_to_speech_no_text_recognized);
    textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "repeat");
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

  private final class OcrVoiceCommandListener implements VoiceCommandManager.Listener {
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
      if (!textToSpeechReady || PreferenceUtils.isTextToSpeechMuted(TextToSpeechLivePreviewActivity.this)) {
        return;
      }
      applySpeechSettings();
      textToSpeech.speak(
          getString(R.string.voice_command_not_recognized), TextToSpeech.QUEUE_FLUSH, null, "voice_feedback");
    }

    @Override
    public void onNoSpeechDetected() {
      // Timed out with no speech; the mic simply stops listening so the user can tap again.
    }

    @Override
    public void onRecognitionError(String message) {
      Toast.makeText(TextToSpeechLivePreviewActivity.this, message, Toast.LENGTH_LONG).show();
    }
  }

  private void updateMuteButtonIcon() {
    boolean muted = PreferenceUtils.isTextToSpeechMuted(this);
    muteButton.setImageResource(
        muted ? R.drawable.ic_volume_off_white_24dp : R.drawable.ic_volume_up_white_24dp);
    muteButton.setContentDescription(
        getString(muted ? R.string.menu_item_unmute_tts : R.string.menu_item_mute_tts));
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

  @Override
  public void onTextRecognized(Text text, List<TextElementGraphic> elementGraphics) {
    latestElementGraphics = elementGraphics;
    updateNoTextDetectedMessage(elementGraphics.isEmpty());
  }

  /**
   * Shows a clear on-screen cue once detection has found nothing for {@link
   * #NO_TEXT_DETECTED_DELAY_MS}, rather than the screen silently doing nothing - a moderately
   * tilted phone (or any other reason OCR isn't picking anything up) should never look broken.
   */
  private void updateNoTextDetectedMessage(boolean noTextDetected) {
    if (!noTextDetected) {
      noTextDetectedSinceMs = 0;
      noTextDetectedMessage.setVisibility(View.GONE);
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (noTextDetectedSinceMs == 0) {
      noTextDetectedSinceMs = now;
    }
    noTextDetectedMessage.setVisibility(
        now - noTextDetectedSinceMs >= NO_TEXT_DETECTED_DELAY_MS ? View.VISIBLE : View.GONE);
  }

  /**
   * Feeds both gesture detectors unconditionally, on every event - each is Android's own
   * tracked state machine and independently decides whether and when to fire its own callback,
   * so there's no hand-rolled tap-vs-pinch classification here to get wrong.
   */
  private boolean onOverlayTouch(View view, MotionEvent event) {
    pinchZoomGestureDetector.onTouchEvent(event);
    tapGestureDetector.onTouchEvent(event);
    return true;
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

  private void speakWord(TextElementGraphic graphic) {
    if (!textToSpeechReady || graphic.getText() == null || graphic.getText().trim().isEmpty()) {
      return;
    }
    if (PreferenceUtils.isTextToSpeechMuted(this)) {
      return;
    }
    startReadingAloud(
        Collections.singletonList(graphic),
        "Word read aloud: \"" + graphic.getText().trim() + "\"");
  }

  private void speakAllText() {
    if (!textToSpeechReady) {
      return;
    }

    // Built from the same graphics that are already on-screen and tappable (crosshair-filtered
    // by TextRecognitionProcessor when crosshair mode is on), so what gets read aloud and
    // highlighted always matches what the user sees, rather than re-deriving a separate,
    // line-level filter over `latestText` that could disagree with it.
    List<TextElementGraphic> words = new ArrayList<>();
    for (TextElementGraphic graphic : latestElementGraphics) {
      if (!graphic.getText().trim().isEmpty()) {
        words.add(graphic);
      }
    }
    if (words.isEmpty()) {
      int emptyMessage =
          PreferenceUtils.isCrosshairModeEnabled(this)
              ? R.string.crosshair_no_text_in_area
              : R.string.text_to_speech_no_text_recognized;
      Toast.makeText(this, emptyMessage, Toast.LENGTH_SHORT).show();
      return;
    }
    if (PreferenceUtils.isTextToSpeechMuted(this)) {
      return;
    }

    startReadingAloud(words, "Text scanned and read aloud");
  }

  /**
   * Speaks {@code words} (already filtered to non-blank ones by the caller) as a single utterance
   * and starts a reading session so exactly one highlight tracks progress through it - used by
   * both word tap and Read All, so they share the same highlight-tracking, detection-freezing,
   * and cooldown-caching behavior instead of two near-duplicate paths.
   *
   * <p>Read in natural reading order (words are already in block-by-block, line-by-line order,
   * matching how TextRecognitionProcessor built them) as a single utterance - not one utterance
   * per word - so the engine keeps its own natural prosody/pausing instead of introducing audible
   * gaps between separately-queued utterances. Per-word offsets are recorded so the highlight can
   * still track progress via onRangeStart as this one utterance plays.
   */
  private void startReadingAloud(List<TextElementGraphic> words, String logDetail) {
    applySpeechSettings();

    WordSequence sequence = buildWordSequence(words);
    int generation = beginReadingSession(words, sequence.starts, sequence.ends);
    textToSpeech.speak(
        sequence.text, TextToSpeech.QUEUE_FLUSH, null, READING_UTTERANCE_PREFIX + generation);
    lastSpokenText = sequence.text;
    VoiceCommandState.recordReadTextSpeech(sequence.text);
    ActivityLogRepository.getInstance(this)
        .logActivity(ActivityLogRepository.TYPE_TEXT_TO_SPEECH, logDetail);
  }

  /** A word sequence's combined utterance text, with each word's [start, end) span within it. */
  private static final class WordSequence {
    final String text;
    final int[] starts;
    final int[] ends;

    WordSequence(String text, int[] starts, int[] ends) {
      this.text = text;
      this.starts = starts;
      this.ends = ends;
    }
  }

  private static WordSequence buildWordSequence(List<TextElementGraphic> words) {
    StringBuilder builder = new StringBuilder();
    int[] starts = new int[words.size()];
    int[] ends = new int[words.size()];
    for (int i = 0; i < words.size(); i++) {
      starts[i] = builder.length();
      builder.append(words.get(i).getText().trim());
      ends[i] = builder.length();
      builder.append(' ');
    }
    return new WordSequence(builder.toString().trim(), starts, ends);
  }

  /**
   * Starts a new read-aloud session: pauses detection so the graphics captured here stay valid
   * (see {@link #detectionPaused}), lights up the first word immediately (rather than waiting for
   * TextToSpeech's onStart, which would add visible lag), shows the "hold the camera steady"
   * banner, and returns a generation id to tag the utterance with so later callbacks can tell
   * whether they still refer to this session or a superseded one.
   */
  private int beginReadingSession(List<TextElementGraphic> words, int[] starts, int[] ends) {
    clearActiveReadingHighlight();
    readingGeneration++;
    setDetectionPaused(true);
    activeReadingWords = words;
    activeReadingWordStarts = starts;
    activeReadingWordEnds = ends;
    holdCameraSteadyMessage.setVisibility(View.VISIBLE);
    setActiveWordIndex(0);
    return readingGeneration;
  }

  /** Ends whichever reading session is current, regardless of how it ended. */
  private void cancelReadingSession() {
    readingGeneration++; // Invalidate any in-flight callback still tagged with the old session.
    clearActiveReadingHighlight();
  }

  private void finishReadingSessionIfCurrent(String utteranceId) {
    Integer generation = parseReadingGeneration(READING_UTTERANCE_PREFIX, utteranceId);
    if (generation == null || generation != readingGeneration) {
      return; // Not a reading utterance, or a stale callback from a superseded one.
    }
    clearActiveReadingHighlight();
  }

  /** Toggles whether the camera analyzer hands frames to the text recognizer. */
  private void setDetectionPaused(boolean paused) {
    detectionPaused = paused;
    if (imageProcessor instanceof TextRecognitionProcessor) {
      ((TextRecognitionProcessor) imageProcessor).setPaused(paused);
    }
  }

  private void clearActiveReadingHighlight() {
    for (TextElementGraphic graphic : activeReadingWords) {
      graphic.setActive(false);
    }
    activeReadingWords = Collections.emptyList();
    activeReadingWordStarts = new int[0];
    activeReadingWordEnds = new int[0];
    holdCameraSteadyMessage.setVisibility(View.GONE);
    setDetectionPaused(false);
    graphicOverlay.postInvalidate();
  }

  private void setActiveWordIndex(int index) {
    for (int i = 0; i < activeReadingWords.size(); i++) {
      activeReadingWords.get(i).setActive(i == index);
    }
    graphicOverlay.postInvalidate();
  }

  /** Returns the index into {@link #activeReadingWords} whose span contains {@code charOffset}. */
  private int findWordIndexForOffset(int charOffset) {
    for (int i = 0; i < activeReadingWordStarts.length; i++) {
      if (charOffset >= activeReadingWordStarts[i] && charOffset < activeReadingWordEnds[i]) {
        return i;
      }
    }
    return -1;
  }

  @Nullable
  private static Integer parseReadingGeneration(String prefix, @Nullable String utteranceId) {
    if (utteranceId == null || !utteranceId.startsWith(prefix)) {
      return null;
    }
    try {
      return Integer.parseInt(utteranceId.substring(prefix.length()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Applies pitch/rate/voice/language preferences to the shared {@link #textToSpeech} instance.
   * Called before every utterance, but the actual native calls - isLanguageAvailable()/
   * setLanguage(), and the {@link #findPreferredVoice} voice-list scan + setVoice() - are skipped
   * whenever nothing has changed since the last call, since those are the expensive part of this
   * setup and the common case (reading several words/lines in a row without touching settings)
   * would otherwise pay that cost before every single utterance.
   */
  private void applySpeechSettings() {
    if (!textToSpeechReady) {
      return;
    }

    Locale localeOverride = PreferenceUtils.getTextToSpeechLocaleOverride(this);
    String voicePreset = PreferenceUtils.getTextToSpeechVoicePreset(this);
    float pitch = PreferenceUtils.getTextToSpeechPitch(this);
    float rate = PreferenceUtils.getTextToSpeechRate(this);

    boolean localeChanged = !Objects.equals(localeOverride, lastAppliedLocaleOverride);
    boolean voiceChanged = localeChanged || !Objects.equals(voicePreset, lastAppliedVoicePreset);

    if (speechSettingsApplied && !localeChanged && !voiceChanged && pitch == lastAppliedPitch
        && rate == lastAppliedRate) {
      return; // Nothing changed since the last utterance - skip re-applying it all.
    }

    if (localeChanged && localeOverride != null) {
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

    textToSpeech.setPitch(pitch);
    textToSpeech.setSpeechRate(rate);
    if (voiceChanged) {
      Voice preferredVoice = findPreferredVoice(voicePreset, localeOverride);
      if (preferredVoice != null) {
        textToSpeech.setVoice(preferredVoice);
      }
    }

    speechSettingsApplied = true;
    lastAppliedLocaleOverride = localeOverride;
    lastAppliedVoicePreset = voicePreset;
    lastAppliedPitch = pitch;
    lastAppliedRate = rate;
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
          if (detectionPaused) {
            // A word tap or "Read All" is in progress: leave the frozen word graphics (and their
            // on-screen positions) alone rather than replacing them mid-read with a new frame.
            imageProxy.close();
            return;
          }
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
