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

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.StrictMode;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import androidx.annotation.NonNull;
import com.google.mlkit.vision.demo.LocaleAwareActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import android.graphics.Typeface;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.vision.demo.BuildConfig;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import java.util.List;
import java.util.Locale;

/** Home screen: pick between the Object Detection, Text to Speech and Navigation demos. */
public final class ChooserActivity extends LocaleAwareActivity {
  private static final String TAG = "ChooserActivity";
  private static final int SECTION_TOGGLE_DURATION_MS = 200;
  private static final int REQUEST_LOCATION_PERMISSION = 100;
  private static final String MAPS_PACKAGE = "com.google.android.apps.maps";

  private DrawerLayout drawerLayout;
  private ViewGroup menuDrawer;

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

    View objectDetectionCard = findViewById(R.id.card_object_detection);
    objectDetectionCard.setOnClickListener(
        v -> startActivity(new Intent(this, CameraXLivePreviewActivity.class)));

    View textToSpeechCard = findViewById(R.id.card_text_to_speech);
    textToSpeechCard.setOnClickListener(
        v -> startActivity(new Intent(this, TextToSpeechLivePreviewActivity.class)));

    View navigationCard = findViewById(R.id.card_navigation);
    navigationCard.setOnClickListener(v -> onNavigationCardClicked());

    setUpMenuDrawer();
  }

  private void onNavigationCardClicked() {
    if (hasLocationPermission()) {
      openMapsAtCurrentLocation();
    } else {
      ActivityCompat.requestPermissions(
          this,
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          REQUEST_LOCATION_PERMISSION);
    }
  }

  private boolean hasLocationPermission() {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != REQUEST_LOCATION_PERMISSION) {
      return;
    }
    if (hasLocationPermission()) {
      openMapsAtCurrentLocation();
    } else {
      openMapsDefaultView();
    }
  }

  private void openMapsAtCurrentLocation() {
    Location location = getLastKnownLocation();
    if (location != null) {
      launchMaps(location.getLatitude(), location.getLongitude());
    } else {
      openMapsDefaultView();
    }
  }

  private void openMapsDefaultView() {
    launchMaps(/* latitude= */ null, /* longitude= */ null);
  }

  private Location getLastKnownLocation() {
    if (!hasLocationPermission()) {
      return null;
    }
    LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    if (locationManager == null) {
      return null;
    }
    Location best = null;
    List<String> providers = locationManager.getProviders(/* enabledOnly= */ true);
    for (String provider : providers) {
      Location candidate;
      try {
        candidate = locationManager.getLastKnownLocation(provider);
      } catch (SecurityException e) {
        Log.w(TAG, "Missing permission to read location from provider " + provider, e);
        continue;
      }
      if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
        best = candidate;
      }
    }
    return best;
  }

  /**
   * Launches Google Maps centered on the given coordinates, or its default view if {@code
   * latitude}/{@code longitude} are null. Falls back to opening Maps in the browser if the Maps
   * app isn't installed.
   */
  private void launchMaps(Double latitude, Double longitude) {
    boolean hasCoordinates = latitude != null && longitude != null;
    Uri geoUri =
        hasCoordinates
            ? Uri.parse(
                String.format(Locale.US, "geo:%1$f,%2$f?q=%1$f,%2$f&z=15", latitude, longitude))
            : Uri.parse("geo:0,0");

    Intent mapsIntent = new Intent(Intent.ACTION_VIEW, geoUri);
    mapsIntent.setPackage(MAPS_PACKAGE);
    if (mapsIntent.resolveActivity(getPackageManager()) != null) {
      startActivity(mapsIntent);
      return;
    }

    Log.w(TAG, "Google Maps app not found, falling back to the browser");
    String webUrl =
        hasCoordinates
            ? String.format(
                Locale.US, "https://maps.google.com/maps?q=%1$f,%2$f&z=15", latitude, longitude)
            : "https://maps.google.com/maps";
    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)));
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
    AppCompatDelegate.setDefaultNightMode(nightMode);
    recreate();
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
