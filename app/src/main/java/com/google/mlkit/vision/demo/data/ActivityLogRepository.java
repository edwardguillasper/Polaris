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

package com.google.mlkit.vision.demo.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single access point for reading and writing {@link ActivityLogEntity} rows off the UI thread. */
public final class ActivityLogRepository {

  /** Activity type recorded for an object detection session. */
  public static final String TYPE_OBJECT_DETECTION = "Object Detection";

  /** Activity type recorded for a text-to-speech read. */
  public static final String TYPE_TEXT_TO_SPEECH = "Text-to-speech";

  private static volatile ActivityLogRepository instance;

  private final ActivityLogDao dao;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

  private ActivityLogRepository(Context context) {
    dao = AppDatabase.getInstance(context).activityLogDao();
  }

  public static ActivityLogRepository getInstance(Context context) {
    if (instance == null) {
      synchronized (ActivityLogRepository.class) {
        if (instance == null) {
          instance = new ActivityLogRepository(context.getApplicationContext());
        }
      }
    }
    return instance;
  }

  /** Records a new activity log entry with the current time as its timestamp. */
  public void logActivity(String type, String detail) {
    long timestamp = System.currentTimeMillis();
    executor.execute(() -> dao.insert(new ActivityLogEntity(type, detail, timestamp)));
  }

  /** Loads all entries sorted newest first and delivers them to {@code callback} on the main thread. */
  public void getAllNewestFirst(Callback<List<ActivityLogEntity>> callback) {
    executor.execute(
        () -> {
          List<ActivityLogEntity> entries = dao.getAllNewestFirst();
          mainThreadHandler.post(() -> callback.onResult(entries));
        });
  }

  /** Simple single-value callback, avoiding a dependency on java.util.function (API 24+). */
  public interface Callback<T> {
    void onResult(T result);
  }
}
