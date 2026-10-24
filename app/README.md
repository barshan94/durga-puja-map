# Durga Puja GPS Helper — Android app

Native Android companion for diagnosing GPS metadata in original photos.

## Current milestone (0.1.0)

- Android system photo picker supports selecting multiple images.
- Reads EXIF latitude/longitude, altitude, and capture timestamp on-device.
- Shows per-photo extraction status and opens found coordinates in Google Maps.
- Does not upload selected photos.

## Build locally

Requirements: JDK 17 and Gradle 8.9.

From the repository root:

```sh
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Verification still required

Install the debug APK on a real Android device and test it against
`PXL_20261021_190537328.jpg`. Expected coordinates from the existing project investigation:
latitude `23.615942`, longitude `90.503164`.

This repository change has not yet been compiled or device-tested. A successful build and
the expected coordinate match are required before treating this milestone as complete.
