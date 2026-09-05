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

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Intent;
import android.os.Bundle;
import android.os.StrictMode;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import com.google.mlkit.vision.demo.MapsNavigator;
import com.google.mlkit.vision.demo.VoiceCommand;
import com.google.mlkit.vision.demo.VoiceCommandManager;
import com.google.mlkit.vision.demo.VoiceCommandState;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.vision.demo.BuildConfig;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Home screen: pick between the Object Detection, Text to Speech and Navigation demos. */
public final class ChooserActivity extends LocaleAwareActivity {
  private static final String TAG = "ChooserActivity";
  private static final int SECTION_TOGGLE_DURATION_MS = 200;
  private static final int THEME_CROSSFADE_DURATION_MS = 300;
  private static final int REQUEST_LOCATION_PERMISSION = 100;
  private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
  private static final int MIC_PULSE_DURATION_MS = 600;

  // Handed off across the recreate() in applyThemeMode(): a snapshot of this screen taken just
  // before the theme switches, so the freshly-recreated instance can crossfade away from it
  // instead of hard-cutting to the new theme. recreate() destroys and creates a new Activity
  // instance in the same process, so a static field is what survives that handoff.
  @Nullable private static Bitmap pendingThemeCrossfadeSnapshot;

  private DrawerLayout drawerLayout;
  private ViewGroup menuDrawer;

  private VoiceCommandManager voiceCommandManager;
  private ImageView voiceCommandIcon;
  @Nullable private ObjectAnimator micPulseAnimator;
  private TextToSpeech voiceTextToSpeech;
  private volatile boolean voiceTextToSpeechReady;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    if (BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(
          new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
      StrictMode.setVmPolicy(
          new StrictMode.VmPolicy.Builder()
              .detectLeakedSqlLiteObjects()
              .detectLeakedClosableObjects()
              .penaltyLog()
              .build());
    }
    super.onCreate(savedInstanceState);
    Log.d(TAG, "onCreate");

    setContentView(R.layout.activity_chooser);
    playThemeCrossfadeIfPending();

    View objectDetectionCard = findViewById(R.id.card_object_detection);
    objectDetectionCard.setOnClickListener(
        v -> startActivity(new Intent(this, CameraXLivePreviewActivity.class)));

    View textToSpeechCard = findViewById(R.id.card_text_to_speech);
    textToSpeechCard.setOnClickListener(
        v -> startActivity(new Intent(this, TextToSpeechLivePreviewActivity.class)));

    View navigationCard = findViewById(R.id.card_navigation);
    navigationCard.setOnClickListener(v -> onNavigationCardClicked());

    setUpVoiceCommand();
    setUpMenuDrawer();
  }

  private void onNavigationCardClicked() {
    if (MapsNavigator.hasLocationPermission(this)) {
      MapsNavigator.openMapsAtCurrentLocation(this);
    } else {
      MapsNavigator.requestLocationPermission(this, REQUEST_LOCATION_PERMISSION);
    }
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_LOCATION_PERMISSION) {
      // openMapsAtCurrentLocation falls back to the default view on its own if permission was
      // denied, so there's no need to branch on grantResults here.
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

  private void setUpVoiceCommand() {
    voiceCommandIcon = findViewById(R.id.voice_command_icon);
    voiceCommandManager = new VoiceCommandManager(this, new HomeVoiceCommandListener());

    voiceTextToSpeech =
        new TextToSpeech(
            this,
            status -> {
              if (status == TextToSpeech.SUCCESS) {
                applyVoiceSpeechSettings();
                voiceTextToSpeechReady = true;
              } else {
                Log.e(TAG, "Text-to-speech initialization failed");
              }
            });

    voiceCommandIcon.setOnClickListener(v -> onVoiceCommandCardClicked());
  }

  private void onVoiceCommandCardClicked() {
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
    voiceCommandIcon.setColorFilter(
        listening ? ContextCompat.getColor(this, R.color.polaris_menu_accent) : 0);
    voiceCommandIcon.setContentDescription(
        getString(
            listening ? R.string.voice_command_mic_listening_desc : R.string.voice_command_mic_desc));

    if (listening) {
      if (micPulseAnimator == null) {
        micPulseAnimator =
            ObjectAnimator.ofPropertyValuesHolder(
                voiceCommandIcon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.15f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f));
        micPulseAnimator.setDuration(MIC_PULSE_DURATION_MS);
        micPulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        micPulseAnimator.setRepeatMode(ObjectAnimator.REVERSE);
      }
      micPulseAnimator.start();
    } else if (micPulseAnimator != null) {
      micPulseAnimator.cancel();
      voiceCommandIcon.setScaleX(1f);
      voiceCommandIcon.setScaleY(1f);
    }
  }

  private void handleVoiceCommand(VoiceCommand command) {
    switch (command) {
      case OPEN_OBJECT_DETECTION:
        startActivity(new Intent(this, CameraXLivePreviewActivity.class));
        break;
      case OPEN_TEXT_TO_SPEECH:
        startActivity(new Intent(this, TextToSpeechLivePreviewActivity.class));
        break;
      case OPEN_NAVIGATION:
        onNavigationCardClicked();
        break;
      case GO_HOME:
        // Already home.
        break;
      case REPEAT:
        handleRepeatVoiceCommand();
        break;
      case STOP:
        if (voiceTextToSpeechReady) {
          voiceTextToSpeech.stop();
        }
        break;
    }
  }

  /** Repeats whichever of the two live-preview screens most recently spoke something. */
  private void handleRepeatVoiceCommand() {
    VoiceCommandState.LastSpokenType lastSpokenType = VoiceCommandState.getLastSpokenType();
    String textToRepeat =
        lastSpokenType == VoiceCommandState.LastSpokenType.OBJECT_DETECTION
            ? VoiceCommandState.getLastDetectedObjectLabel()
            : lastSpokenType == VoiceCommandState.LastSpokenType.TEXT_TO_SPEECH
                ? VoiceCommandState.getLastReadText()
                : null;
    speakVoiceFeedback(
        textToRepeat == null || textToRepeat.isEmpty()
            ? getString(R.string.voice_command_nothing_to_repeat)
            : textToRepeat);
  }

  private void speakVoiceFeedback(String text) {
    if (!voiceTextToSpeechReady) {
      return;
    }
    applyVoiceSpeechSettings();
    voiceTextToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_feedback");
  }

  private void applyVoiceSpeechSettings() {
    if (!voiceTextToSpeechReady) {
      return;
    }

    Locale localeOverride = PreferenceUtils.getTextToSpeechLocaleOverride(this);
    if (localeOverride != null) {
      int availability = voiceTextToSpeech.isLanguageAvailable(localeOverride);
      Log.d(
          TAG,
          "TextToSpeech.isLanguageAvailable("
              + localeOverride
              + ") returned "
              + PreferenceUtils.describeTextToSpeechLanguageResult(availability));
      int languageResult =
          availability >= TextToSpeech.LANG_AVAILABLE
              ? voiceTextToSpeech.setLanguage(localeOverride)
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

    voiceTextToSpeech.setPitch(PreferenceUtils.getTextToSpeechPitch(this));
    voiceTextToSpeech.setSpeechRate(PreferenceUtils.getTextToSpeechRate(this));
    Voice preferredVoice =
        findPreferredVoice(PreferenceUtils.getTextToSpeechVoicePreset(this), localeOverride);
    if (preferredVoice != null) {
      voiceTextToSpeech.setVoice(preferredVoice);
    }
  }

  private Voice findPreferredVoice(String voicePreset, @Nullable Locale localeOverride) {
    Set<Voice> voices = voiceTextToSpeech.getVoices();
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
    Voice defaultVoice = voiceTextToSpeech.getDefaultVoice();
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

  private final class HomeVoiceCommandListener implements VoiceCommandManager.Listener {
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
      speakVoiceFeedback(getString(R.string.voice_command_not_recognized));
    }

    @Override
    public void onNoSpeechDetected() {
      // Timed out with no speech; the mic simply stops listening so the user can tap again.
    }

    @Override
    public void onRecognitionError(String message) {
      Toast.makeText(ChooserActivity.this, message, Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (micPulseAnimator != null) {
      micPulseAnimator.cancel();
    }
    voiceCommandManager.destroy();
    voiceTextToSpeech.stop();
    voiceTextToSpeech.shutdown();
  }

  private void setUpMenuDrawer() {
    drawerLayout = findViewById(R.id.drawer_layout);
    menuDrawer = findViewById(R.id.menu_drawer);

    View menuButton = findViewById(R.id.menu_button);
    menuButton.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

    View closeButton = findViewById(R.id.menu_close_button);
    closeButton.setOnClickListener(v -> drawerLayout.closeDrawer(GravityCompat.START));

    TextView howYouUseText = findViewById(R.id.menu_item_how_you_use_text);
    String brandName = getString(R.string.app_name);
    SpannableStringBuilder label =
        new SpannableStringBuilder(getString(R.string.menu_item_how_you_use_prefix))
            .append(" ")
            .append(brandName);
    int brandStart = label.length() - brandName.length();
    label.setSpan(
        new ForegroundColorSpan(ContextCompat.getColor(this, R.color.polaris_menu_accent)),
        brandStart,
        label.length(),
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    howYouUseText.setText(label);

    setUpExpandableSection(
        R.id.menu_item_how_you_use, R.id.menu_item_how_you_use_chevron, R.id.menu_submenu_how_you_use);
    setUpExpandableSection(R.id.menu_item_help, R.id.menu_item_help_chevron, R.id.menu_submenu_help);
    setUpExpandableSection(R.id.menu_item_account, R.id.menu_item_account_chevron, R.id.menu_submenu_account);
    setUpExpandableSection(
        R.id.menu_item_appearance, R.id.menu_item_appearance_chevron, R.id.menu_submenu_appearance);
    setUpThemeOptions();
    setUpExpandableSection(
        R.id.menu_item_language, R.id.menu_item_language_chevron, R.id.menu_submenu_language);
    setUpLanguageOptions();

    int[] subItemIds = {
      R.id.menu_subitem_log_out,
    };
    for (int subItemId : subItemIds) {
      setUpSubItemPlaceholder(subItemId);
    }

    findViewById(R.id.menu_subitem_activity)
        .setOnClickListener(
            v -> {
              drawerLayout.closeDrawer(GravityCompat.START);
              startActivity(new Intent(this, ActivityLogActivity.class));
            });

    findViewById(R.id.menu_subitem_user_guide)
        .setOnClickListener(
            v -> {
              drawerLayout.closeDrawer(GravityCompat.START);
              startActivity(new Intent(this, UserGuideActivity.class));
            });

    findViewById(R.id.menu_subitem_faq)
        .setOnClickListener(
            v -> {
              drawerLayout.closeDrawer(GravityCompat.START);
              startActivity(new Intent(this, FaqActivity.class));
            });

    findViewById(R.id.menu_subitem_device_permissions)
        .setOnClickListener(
            v -> {
              drawerLayout.closeDrawer(GravityCompat.START);
              startActivity(new Intent(this, DevicePermissionsActivity.class));
            });

    findViewById(R.id.menu_subitem_notifications)
        .setOnClickListener(
            v -> {
              drawerLayout.closeDrawer(GravityCompat.START);
              startActivity(new Intent(this, NotificationsActivity.class));
            });

    findViewById(R.id.menu_subitem_manage_account)
        .setOnClickListener(
            v -> {
              drawerLayout.closeDrawer(GravityCompat.START);
              startActivity(new Intent(this, ManageAccountActivity.class));
            });
  }

  private void setUpThemeOptions() {
    TextView lightOption = findViewById(R.id.menu_subitem_theme_light);
    TextView darkOption = findViewById(R.id.menu_subitem_theme_dark);
    TextView systemOption = findViewById(R.id.menu_subitem_theme_system);

    lightOption.setOnClickListener(v -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO));
    darkOption.setOnClickListener(v -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES));
    systemOption.setOnClickListener(
        v -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));

    updateThemeOptionSelection(lightOption, darkOption, systemOption);
  }

  private void applyThemeMode(int nightMode) {
    PreferenceUtils.setThemeMode(this, nightMode);
    // recreate() relaunches this Activity in place via an internal relaunch, not the normal
    // start/finish flow, so overridePendingTransition has no effect on it. Snapshotting the
    // current screen and crossfading it away in the new instance (see
    // playThemeCrossfadeIfPending) is what actually makes the switch a smooth blend.
    pendingThemeCrossfadeSnapshot = captureWindowSnapshot();
    AppCompatDelegate.setDefaultNightMode(nightMode);
    recreate();
  }

  /** Renders the current window content into a bitmap for {@link #applyThemeMode}. */
  @Nullable
  private Bitmap captureWindowSnapshot() {
    View root = getWindow().getDecorView().findViewById(android.R.id.content);
    if (root == null || root.getWidth() == 0 || root.getHeight() == 0) {
      return null;
    }
    Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
    root.draw(new Canvas(bitmap));
    return bitmap;
  }

  /**
   * If a pre-theme-switch snapshot is waiting (see {@link #applyThemeMode}), lays it over the
   * newly recreated (already re-themed) screen and fades it out, so the switch reads as a
   * crossfade rather than an instant cut. No-op otherwise, e.g. on a normal cold start.
   */
  private void playThemeCrossfadeIfPending() {
    Bitmap snapshot = pendingThemeCrossfadeSnapshot;
    pendingThemeCrossfadeSnapshot = null;
    if (snapshot == null || snapshot.isRecycled()) {
      return;
    }

    ViewGroup contentRoot = getWindow().getDecorView().findViewById(android.R.id.content);
    ImageView overlay = new ImageView(this);
    overlay.setScaleType(ImageView.ScaleType.FIT_XY);
    overlay.setImageBitmap(snapshot);
    contentRoot.addView(
        overlay,
        new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    overlay
        .animate()
        .alpha(0f)
        .setDuration(THEME_CROSSFADE_DURATION_MS)
        .setInterpolator(new AccelerateInterpolator())
        .withEndAction(
            () -> {
              contentRoot.removeView(overlay);
              snapshot.recycle();
            })
        .start();
  }

  private void updateThemeOptionSelection(
      TextView lightOption, TextView darkOption, TextView systemOption) {
    int selectedMode = PreferenceUtils.getThemeMode(this);
    markSelectedOption(lightOption, selectedMode == AppCompatDelegate.MODE_NIGHT_NO);
    markSelectedOption(darkOption, selectedMode == AppCompatDelegate.MODE_NIGHT_YES);
    markSelectedOption(
        systemOption, selectedMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
  }

  private void setUpLanguageOptions() {
    TextView englishOption = findViewById(R.id.menu_subitem_language_english);
    TextView tagalogOption = findViewById(R.id.menu_subitem_language_tagalog);

    englishOption.setOnClickListener(v -> applyLanguage(PreferenceUtils.LANGUAGE_ENGLISH));
    tagalogOption.setOnClickListener(v -> applyLanguage(PreferenceUtils.LANGUAGE_TAGALOG));

    String selectedLanguage = PreferenceUtils.getAppLanguage(this);
    markSelectedOption(
        englishOption, PreferenceUtils.LANGUAGE_ENGLISH.equals(selectedLanguage));
    markSelectedOption(
        tagalogOption, PreferenceUtils.LANGUAGE_TAGALOG.equals(selectedLanguage));
  }

  private void applyLanguage(String languageCode) {
    if (languageCode.equals(PreferenceUtils.getAppLanguage(this))) {
      return;
    }
    PreferenceUtils.setAppLanguage(this, languageCode);

    // Restart the task from Home so every screen re-attaches with the new locale, including
    // any screens already sitting in the back stack (e.g. Get Started, Login).
    Intent intent = new Intent(this, ChooserActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    startActivity(intent);
    finish();
  }

  private void markSelectedOption(TextView option, boolean selected) {
    option.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    option.setTextColor(
        ContextCompat.getColor(
            this, selected ? R.color.polaris_menu_accent : R.color.polaris_menu_subitem_text));
  }

  private void setUpExpandableSection(int headerId, int chevronId, int submenuId) {
    View header = findViewById(headerId);
    View chevron = findViewById(chevronId);
    View submenu = findViewById(submenuId);

    header.setOnClickListener(
        v -> {
          boolean expanding = submenu.getVisibility() != View.VISIBLE;

          TransitionManager.beginDelayedTransition(
              menuDrawer, new AutoTransition().setDuration(SECTION_TOGGLE_DURATION_MS));
          submenu.setVisibility(expanding ? View.VISIBLE : View.GONE);

          chevron.animate().rotation(expanding ? 90f : 0f).setDuration(SECTION_TOGGLE_DURATION_MS).start();
        });
  }

  private void setUpSubItemPlaceholder(int subItemId) {
    TextView subItem = findViewById(subItemId);
    subItem.setOnClickListener(
        v -> Toast.makeText(this, subItem.getText(), Toast.LENGTH_SHORT).show());
  }

  @Override
  public void onBackPressed() {
    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
      drawerLayout.closeDrawer(GravityCompat.START);
      return;
    }
    super.onBackPressed();
  }
}
