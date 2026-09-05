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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Wraps Android's on-device {@link SpeechRecognizer} to listen for a single utterance and match
 * it against Polaris's {@link VoiceCommand} set. One instance is owned per activity that exposes
 * a microphone entry point.
 */
public class VoiceCommandManager {

  private static final String TAG = "VoiceCommandManager";

  /** Receives voice-listening lifecycle events and recognition outcomes. */
  public interface Listener {
    /** Called whenever the mic starts or stops actively listening; drives the UI's mic state. */
    void onListeningStateChanged(boolean listening);

    /** One of the 5 supported commands was recognized. */
    void onCommandRecognized(VoiceCommand command);

    /** Speech was recognized but didn't match any supported command. */
    void onCommandNotRecognized(String heardText);

    /** Listening timed out, or nothing intelligible was heard; the user can just tap again. */
    void onNoSpeechDetected();

    /** Recognition could not proceed at all (no permission, no recognizer on this device, ...). */
    void onRecognitionError(String message);
  }

  private final Activity activity;
  private final Listener listener;
  @Nullable private SpeechRecognizer speechRecognizer;
  private boolean listening;

  public VoiceCommandManager(Activity activity, Listener listener) {
    this.activity = activity;
    this.listener = listener;
  }

  public static boolean hasRecordAudioPermission(Context context) {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }

  public static void requestRecordAudioPermission(Activity activity, int requestCode) {
    ActivityCompat.requestPermissions(
        activity, new String[] {Manifest.permission.RECORD_AUDIO}, requestCode);
  }

  public boolean isListening() {
    return listening;
  }

  /** Starts listening for a single command. No-ops if already listening. */
  public void startListening() {
    if (listening) {
      return;
    }
    if (!hasRecordAudioPermission(activity)) {
      listener.onRecognitionError(activity.getString(R.string.voice_command_permission_denied));
      return;
    }
    if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
      listener.onRecognitionError(activity.getString(R.string.voice_command_unavailable));
      return;
    }

    if (speechRecognizer == null) {
      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity);
      speechRecognizer.setRecognitionListener(new InternalListener());
    }

    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
    intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
    speechRecognizer.startListening(intent);
  }

  /** Cancels an in-progress listening session, e.g. because the user tapped the mic again. */
  public void stopListening() {
    if (speechRecognizer != null) {
      speechRecognizer.cancel();
    }
    setListening(false);
  }

  /** Releases the underlying recognizer. Call from the owning activity's onDestroy. */
  public void destroy() {
    if (speechRecognizer != null) {
      speechRecognizer.destroy();
      speechRecognizer = null;
    }
  }

  private void setListening(boolean listening) {
    if (this.listening == listening) {
      return;
    }
    this.listening = listening;
    listener.onListeningStateChanged(listening);
  }

  private void handleResults(@Nullable ArrayList<String> matches) {
    if (matches == null || matches.isEmpty()) {
      listener.onNoSpeechDetected();
      return;
    }
    for (String candidate : matches) {
      VoiceCommand command = VoiceCommand.match(candidate);
      if (command != null) {
        listener.onCommandRecognized(command);
        return;
      }
    }
    listener.onCommandNotRecognized(matches.get(0));
  }

  private final class InternalListener implements RecognitionListener {
    @Override
    public void onReadyForSpeech(Bundle params) {
      setListening(true);
    }

    @Override
    public void onBeginningOfSpeech() {}

    @Override
    public void onRmsChanged(float rmsdB) {}

    @Override
    public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
      setListening(false);
      if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
        listener.onNoSpeechDetected();
        return;
      }
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
        listener.onRecognitionError(activity.getString(R.string.voice_command_permission_denied));
        return;
      }
      Log.w(TAG, "Speech recognition error: " + error);
      listener.onRecognitionError(activity.getString(R.string.voice_command_not_recognized));
    }

    @Override
    public void onResults(Bundle results) {
      setListening(false);
      handleResults(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }

    @Override
    public void onPartialResults(Bundle partialResults) {}

    @Override
    public void onEvent(int eventType, Bundle params) {}
  }
}
