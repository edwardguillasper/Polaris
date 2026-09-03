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

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** A single row in the activity log: what feature was used, what happened, and when. */
@Entity(tableName = "activity_log")
public class ActivityLogEntity {

  @PrimaryKey(autoGenerate = true)
  private long id;

  @NonNull
  @ColumnInfo(name = "type")
  private final String type;

  @NonNull
  @ColumnInfo(name = "detail")
  private final String detail;

  @ColumnInfo(name = "timestamp")
  private final long timestamp;

  public ActivityLogEntity(@NonNull String type, @NonNull String detail, long timestamp) {
    this.type = type;
    this.detail = detail;
    this.timestamp = timestamp;
  }

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  @NonNull
  public String getType() {
    return type;
  }

  @NonNull
  public String getDetail() {
    return detail;
  }

  public long getTimestamp() {
    return timestamp;
  }
}
