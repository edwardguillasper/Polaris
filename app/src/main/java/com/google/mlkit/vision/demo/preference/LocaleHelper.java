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

package com.google.mlkit.vision.demo.preference;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.LocaleList;
import java.util.Locale;

/** Wraps a {@link Context} with the user's saved app-language preference. */
public final class LocaleHelper {

  public static Context wrap(Context context) {
    Locale locale = new Locale(PreferenceUtils.getAppLanguage(context));
    Locale.setDefault(locale);

    Configuration configuration = new Configuration(context.getResources().getConfiguration());
    configuration.setLocale(locale);
    if (VERSION.SDK_INT >= VERSION_CODES.N) {
      configuration.setLocales(new LocaleList(locale));
    }
    return context.createConfigurationContext(configuration);
  }

  private LocaleHelper() {}
}
