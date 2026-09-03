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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.data.ActivityLogEntity;
import com.google.mlkit.vision.demo.data.ActivityLogRepository;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shows real usage history: what features were used, what happened, and when. */
public final class ActivityLogActivity extends LocaleAwareActivity {

  private LinearLayout listContainer;
  private View emptyState;
  private int gradientStartColor;
  private int gradientEndColor;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_activity_log);

    gradientStartColor = ContextCompat.getColor(this, R.color.polaris_menu_accent);
    gradientEndColor = ContextCompat.getColor(this, R.color.polaris_blue_deep);

    listContainer = findViewById(R.id.activity_log_list);
    emptyState = findViewById(R.id.activity_log_empty_state);

    findViewById(R.id.activity_log_back_button).setOnClickListener(v -> finish());

    BrandGradient.applyHorizontalGradient(
        findViewById(R.id.activity_log_title), gradientStartColor, gradientEndColor);
    BrandGradient.applyWordGradient(
        findViewById(R.id.activity_log_heading),
        getString(R.string.activity_log_heading),
        "Polaris",
        gradientStartColor,
        gradientEndColor);
    BrandGradient.applyLogoGradient(
        (ImageView) findViewById(R.id.activity_log_watermark), gradientStartColor, gradientEndColor);
  }

  @Override
  protected void onResume() {
    super.onResume();
    // Reload every time the screen becomes visible so entries logged elsewhere show up on return.
    ActivityLogRepository.getInstance(this).getAllNewestFirst(this::showEntries);
  }

  private void showEntries(List<ActivityLogEntity> entries) {
    listContainer.removeAllViews();
    emptyState.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);

    LayoutInflater inflater = LayoutInflater.from(this);
    for (ActivityLogEntity entry : entries) {
      View row = inflater.inflate(R.layout.item_activity_log, listContainer, false);
      TextView typeView = row.findViewById(R.id.activity_log_item_type);
      typeView.setText(entry.getType());
      BrandGradient.applyHorizontalGradient(typeView, gradientStartColor, gradientEndColor);
      ((TextView) row.findViewById(R.id.activity_log_item_detail)).setText(entry.getDetail());
      ((TextView) row.findViewById(R.id.activity_log_item_timestamp))
          .setText(formatTimestamp(entry.getTimestamp()));
      listContainer.addView(row);
    }
  }

  private static String formatTimestamp(long timestampMillis) {
    Calendar entryDay = Calendar.getInstance();
    entryDay.setTimeInMillis(timestampMillis);

    Calendar today = Calendar.getInstance();
    Calendar yesterday = Calendar.getInstance();
    yesterday.add(Calendar.DAY_OF_YEAR, -1);

    String time = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(timestampMillis));

    if (isSameDay(entryDay, today)) {
      return "Today, " + time;
    }
    if (isSameDay(entryDay, yesterday)) {
      return "Yesterday, " + time;
    }
    return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(new Date(timestampMillis));
  }

  private static boolean isSameDay(Calendar a, Calendar b) {
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
  }
}
