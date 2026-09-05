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
import java.util.Locale;

/**
 * The voice commands Polaris understands. Matching is deliberately forgiving: {@link
 * #match(String)} first looks for any of a command's known phrases appearing verbatim, then falls
 * back to fuzzy, word-by-word matching so slight rewordings or minor mishearing by the speech
 * recognizer (missing a word, a letter or two off) still resolve to the right command.
 */
public enum VoiceCommand {
  OPEN_OBJECT_DETECTION(
      "object detection",
      "detect object",
      "detect objects",
      "start detection",
      "start object detection",
      "open detection",
      "open the camera",
      "identify object",
      "identify objects",
      "what is this",
      "camera"),
  OPEN_TEXT_TO_SPEECH(
      "text to speech",
      "text-to-speech",
      "read text",
      "read the text",
      "scan text",
      "start reading",
      "reading mode",
      "read aloud",
      "read this"),
  OPEN_NAVIGATION(
      "navigate",
      "navigation",
      "open navigation",
      "open maps",
      "maps",
      "directions",
      "get directions"),
  GO_HOME(
      "go home",
      "go back home",
      "back to home",
      "back home",
      "home screen",
      "main menu",
      "main screen",
      "go back",
      "home"),
  REPEAT(
      "repeat",
      "repeat that",
      "repeat it",
      "say again",
      "say that again",
      "one more time",
      "what did you say",
      "again"),
  STOP(
      "stop",
      "cancel",
      "stop talking",
      "be quiet",
      "quiet",
      "silence");

  // Fuzzy word matching only kicks in for words at least this long; shorter words (like "go" or
  // "you") are common enough, and short enough, that tolerating typos in them risks false
  // positives, so those must match exactly.
  private static final int MIN_FUZZY_WORD_LENGTH = 4;

  // A phrase is considered heard if more than half its words are found (exactly or fuzzily)
  // among the recognized words.
  private static final double FUZZY_MATCH_THRESHOLD = 0.5;

  private final String[] phrases;

  VoiceCommand(String... phrases) {
    this.phrases = phrases;
  }

  /**
   * Returns the command that best matches {@code heardText}, or null if nothing matches closely
   * enough. Tries an exact phrase match first; if none is found, falls back to fuzzy matching.
   */
  @Nullable
  public static VoiceCommand match(@Nullable String heardText) {
    if (heardText == null) {
      return null;
    }
    String normalized = normalize(heardText);
    if (normalized.isEmpty()) {
      return null;
    }

    for (VoiceCommand command : values()) {
      for (String phrase : command.phrases) {
        if (normalized.contains(phrase)) {
          return command;
        }
      }
    }

    return fuzzyMatch(normalized);
  }

  /**
   * Falls back to word-level fuzzy matching when no phrase appears verbatim: recognized speech is
   * rarely a clean transcript, so this tolerates a missing filler word or a word or two that the
   * recognizer got slightly wrong.
   */
  @Nullable
  private static VoiceCommand fuzzyMatch(String normalizedHeardText) {
    String[] heardWords = normalizedHeardText.split(" ");

    VoiceCommand bestCommand = null;
    double bestScore = FUZZY_MATCH_THRESHOLD;
    for (VoiceCommand command : values()) {
      for (String phrase : command.phrases) {
        double score = fuzzyPhraseScore(phrase, heardWords);
        if (score > bestScore) {
          bestScore = score;
          bestCommand = command;
        }
      }
    }
    return bestCommand;
  }

  /** Fraction of {@code phrase}'s words that fuzzily appear among {@code heardWords}. */
  private static double fuzzyPhraseScore(String phrase, String[] heardWords) {
    String[] phraseWords = phrase.split(" ");
    int matched = 0;
    for (String phraseWord : phraseWords) {
      if (hasFuzzyMatch(phraseWord, heardWords)) {
        matched++;
      }
    }
    return (double) matched / phraseWords.length;
  }

  private static boolean hasFuzzyMatch(String phraseWord, String[] heardWords) {
    for (String heardWord : heardWords) {
      if (heardWord.equals(phraseWord)) {
        return true;
      }
      if (phraseWord.length() < MIN_FUZZY_WORD_LENGTH || heardWord.length() < MIN_FUZZY_WORD_LENGTH) {
        continue;
      }
      // Requiring the same first letter keeps this from matching unrelated short-ish words that
      // merely happen to be a short edit apart (e.g. "some" vs "home").
      if (heardWord.charAt(0) != phraseWord.charAt(0)) {
        continue;
      }
      if (levenshteinDistance(phraseWord, heardWord) <= maxEditDistance(phraseWord.length())) {
        return true;
      }
    }
    return false;
  }

  private static int maxEditDistance(int wordLength) {
    return wordLength <= 6 ? 1 : 2;
  }

  private static String normalize(String text) {
    return text.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").trim().replaceAll(" +", " ");
  }

  private static int levenshteinDistance(String a, String b) {
    int[][] distances = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) {
      distances[i][0] = i;
    }
    for (int j = 0; j <= b.length(); j++) {
      distances[0][j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int substitutionCost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        distances[i][j] =
            Math.min(
                Math.min(distances[i - 1][j] + 1, distances[i][j - 1] + 1),
                distances[i - 1][j - 1] + substitutionCost);
      }
    }
    return distances[a.length()][b.length()];
  }
}
