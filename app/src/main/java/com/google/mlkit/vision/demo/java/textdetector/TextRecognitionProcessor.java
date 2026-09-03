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
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.demo.GraphicOverlay;
import com.google.mlkit.vision.demo.java.VisionProcessorBase;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

/**
 * Processor for the text-to-speech demo. Draws a box around each recognized word and reports the
 * latest result to a listener so the hosting activity can drive tap-to-speak and "Read All".
 */
public class TextRecognitionProcessor extends VisionProcessorBase<Text> {

  /** Reports the latest recognized text and its on-screen word graphics. */
  public interface OnTextRecognizedListener {
    void onTextRecognized(Text text, List<TextElementGraphic> elementGraphics);
  }

  private static final String TAG = "TextRecProcessor";

  private final TextRecognizer textRecognizer;
  private final OnTextRecognizedListener listener;

  public TextRecognitionProcessor(Context context, OnTextRecognizedListener listener) {
    super(context);
    this.listener = listener;
    textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
  }

  @Override
  public void stop() {
    super.stop();
    textRecognizer.close();
  }

  @Override
  protected Task<Text> detectInImage(InputImage image) {
    return textRecognizer.process(image);
  }

  @Override
  protected void onSuccess(@NonNull Text text, @NonNull GraphicOverlay graphicOverlay) {
    List<TextElementGraphic> elementGraphics = new ArrayList<>();
    for (Text.TextBlock block : text.getTextBlocks()) {
      for (Text.Line line : block.getLines()) {
        for (Text.Element element : line.getElements()) {
          TextElementGraphic graphic = new TextElementGraphic(graphicOverlay, element);
          graphicOverlay.add(graphic);
          elementGraphics.add(graphic);
        }
      }
    }
    if (listener != null) {
      listener.onTextRecognized(text, elementGraphics);
    }
  }

  @Override
  protected void onFailure(@NonNull Exception e) {
    Log.w(TAG, "Text detection failed.", e);
  }
}
