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

import androidx.annotation.Nullable;

/**
 * App-wide, in-memory record of the most recently spoken content, so the home screen's "Repeat"
 * voice command can decide whether to replay the last detected object label or the last
 * read-aloud text: whichever was produced most recently is the contextually relevant one.
 */
public final class VoiceCommandState {

  /** Which of the two screens most recently spoke something. */
  public enum LastSpokenType {
    NONE,
    OBJECT_DETECTION,
    TEXT_TO_SPEECH
  }

  @Nullable private static volatile String lastDetectedObjectLabel;
  @Nullable private static volatile String lastReadText;
  private static volatile LastSpokenType lastSpokenType = LastSpokenType.NONE;

  private VoiceCommandState() {}

  public static void recordDetectedObjectSpeech(String text) {
    lastDetectedObjectLabel = text;
    lastSpokenType = LastSpokenType.OBJECT_DETECTION;
  }

  public static void recordReadTextSpeech(String text) {
    lastReadText = text;
    lastSpokenType = LastSpokenType.TEXT_TO_SPEECH;
  }

  public static LastSpokenType getLastSpokenType() {
    return lastSpokenType;
  }

  @Nullable
  public static String getLastDetectedObjectLabel() {
    return lastDetectedObjectLabel;
  }

  @Nullable
  public static String getLastReadText() {
    return lastReadText;
  }
}
