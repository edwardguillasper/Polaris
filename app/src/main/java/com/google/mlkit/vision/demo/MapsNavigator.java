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
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.Locale;

/**
 * Launches Google Maps centered on the device's last known location, falling back to its default
 * view if location isn't available. Shared by every screen that exposes a "Navigation" action:
 * the home screen's Navigation card and the "Open Navigation" voice command on every screen.
 */
public final class MapsNavigator {

  private static final String TAG = "MapsNavigator";
  private static final String MAPS_PACKAGE = "com.google.android.apps.maps";

  private MapsNavigator() {}

  public static boolean hasLocationPermission(Context context) {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  public static void requestLocationPermission(Activity activity, int requestCode) {
    ActivityCompat.requestPermissions(
        activity,
        new String[] {
          Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        },
        requestCode);
  }

  /** Opens Maps at the device's current location if known, or its default view otherwise. */
  public static void openMapsAtCurrentLocation(Activity activity) {
    Location location = hasLocationPermission(activity) ? getLastKnownLocation(activity) : null;
    if (location != null) {
      launchMaps(activity, location.getLatitude(), location.getLongitude());
    } else {
      launchMaps(activity, /* latitude= */ null, /* longitude= */ null);
    }
  }

  @Nullable
  private static Location getLastKnownLocation(Activity activity) {
    LocationManager locationManager =
        (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
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
  private static void launchMaps(Activity activity, @Nullable Double latitude, @Nullable Double longitude) {
    boolean hasCoordinates = latitude != null && longitude != null;
    Uri geoUri =
        hasCoordinates
            ? Uri.parse(
                String.format(Locale.US, "geo:%1$f,%2$f?q=%1$f,%2$f&z=15", latitude, longitude))
            : Uri.parse("geo:0,0");

    Intent mapsIntent = new Intent(Intent.ACTION_VIEW, geoUri);
    mapsIntent.setPackage(MAPS_PACKAGE);
    if (mapsIntent.resolveActivity(activity.getPackageManager()) != null) {
      activity.startActivity(mapsIntent);
      return;
    }

    Log.w(TAG, "Google Maps app not found, falling back to the browser");
    String webUrl =
        hasCoordinates
            ? String.format(
                Locale.US, "https://maps.google.com/maps?q=%1$f,%2$f&z=15", latitude, longitude)
            : "https://maps.google.com/maps";
    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)));
  }
}
