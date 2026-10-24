# Durga Puja GPS Helper — Android app

Native Android companion for diagnosing GPS metadata in original photos.

## Current milestone (0.2.0)

- Uses Android's document picker to select multiple images and matching Google Photos JSON sidecars together.
- Displays provider-reported filenames rather than relying on opaque URI path IDs.
- Reads EXIF latitude/longitude, altitude, and capture timestamp on-device.
- Reads Google Photos sidecar `geoDataExif` / `geoData` coordinates and capture timestamps, matching sidecars to images by filename.
- Shows per-image diagnostics and opens found coordinates in Google Maps.
- Does not upload selected files.

## Build locally

Requirements: JDK 17 and Gradle 8.9.

From the repository root:

```sh
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Device verification still required

Test the app against `PXL_20261021_190537328.jpg`. The existing project investigation recorded expected coordinates around latitude `23.615942`, longitude `90.503164`; these must be verified against the actual image/sidecar data.

The code change must pass the Android build before a new APK is installed. A successful build does not by itself prove that the target photo contains or exposes readable GPS coordinates.
