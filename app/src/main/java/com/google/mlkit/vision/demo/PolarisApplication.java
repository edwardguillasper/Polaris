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

import android.content.Context;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDexApplication;
import com.google.mlkit.vision.demo.preference.LocaleHelper;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;

/**
 * Applies the user's saved light/dark/system theme and app-language preferences before any
 * activity is created.
 */
public class PolarisApplication extends MultiDexApplication {

  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(LocaleHelper.wrap(base));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    AppCompatDelegate.setDefaultNightMode(PreferenceUtils.getThemeMode(this));
  }
}
