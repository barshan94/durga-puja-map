package com.barshan94.durgapujagps

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhotoGps(
    val uri: Uri,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val capturedAt: String?,
    val details: String
) {
    val hasGps: Boolean get() = latitude != null && longitude != null
}

private data class SidecarGps(
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val capturedAt: String?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GpsHelperScreen(
                        readPhotos = { uris -> withContext(Dispatchers.IO) { readSelectedFiles(uris) } },
                        openMap = { lat, lon ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))
                            intent.setPackage("com.google.android.apps.maps")
                            try {
                                startActivity(intent)
                            } catch (_: Exception) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
            }
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "Selected file"
        } finally {
            cursor?.close()
        }
    }

    private fun normalizedBase(filename: String): String {
        var name = filename.substringAfterLast('/').trim().lowercase(Locale.ROOT)
        name = name.replace(Regex("(?i)\\.(supplemental-metadata|metadata)\\.json$"), "")
        name = name.removeSuffix(".json")
        return name.replace(Regex("\\.(jpg|jpeg|png|heic|heif|webp|tif|tiff)$", RegexOption.IGNORE_CASE), "")
    }

    private fun readSelectedFiles(uris: List<Uri>): List<PhotoGps> {
        val named = uris.map { it to displayName(it) }
        val sidecars = mutableMapOf<String, SidecarGps>()
        val sidecarUris = mutableSetOf<Uri>()

        for ((uri, name) in named) {
            if (!name.endsWith(".json", ignoreCase = true)) continue
            try {
                val jsonText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: continue
                val json = JSONObject(jsonText)
                val gps = parseSidecar(name, json)
                if (gps.latitude != null && gps.longitude != null) {
                    sidecars[normalizedBase(name)] = gps
                    sidecarUris.add(uri)
                }
            } catch (_: Exception) {
                // Keep going; a malformed or unrelated JSON file should not block image inspection.
            }
        }

        val imageFiles = named.filterNot { (uri, name) ->
            name.endsWith(".json", ignoreCase = true) || uri in sidecarUris
        }
        return imageFiles.map { (uri, name) ->
            val imageResult = readPhoto(uri, name)
            if (imageResult.hasGps) {
                imageResult
            } else {
                val sidecar = sidecars[normalizedBase(name)]
                if (sidecar != null) {
                    imageResult.copy(
                        latitude = sidecar.latitude,
                        longitude = sidecar.longitude,
                        altitude = imageResult.altitude ?: sidecar.altitude,
                        capturedAt = imageResult.capturedAt ?: sidecar.capturedAt,
                        details = "GPS coordinates recovered from matching Google Photos JSON sidecar."
                    )
                } else if (sidecars.isNotEmpty()) {
                    imageResult.copy(details = imageResult.details + " A GPS sidecar was selected, but its filename did not match this image.")
                } else {
                    imageResult.copy(details = imageResult.details + " Select the image and its matching Google Photos .json sidecar together if one exists.")
                }
            }
        }
    }

    private fun parseSidecar(name: String, json: JSONObject): SidecarGps {
        val exifGeo = json.optJSONObject("geoDataExif")
        val geo = json.optJSONObject("geoData")
        val selectedGeo = exifGeo?.takeIf { it.has("latitude") && it.has("longitude") } ?: geo
        val lat = selectedGeo?.optDouble("latitude")?.takeIf { it.isFinite() && it != 0.0 }
        val lon = selectedGeo?.optDouble("longitude")?.takeIf { it.isFinite() && it != 0.0 }
        val altitude = selectedGeo?.optDouble("altitude")?.takeIf { it.isFinite() }
        val timestamp = json.optJSONObject("photoTakenTime")?.optString("timestamp")
        val capturedAt = timestamp?.toLongOrNull()?.let {
            try {
                SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it * 1000))
            } catch (_: Exception) { null }
        }
        return SidecarGps(name, lat, lon, altitude, capturedAt)
    }

    private fun readPhoto(uri: Uri, name: String): PhotoGps {
        var temporaryDat: java.io.File? = null
        return try {
            // Work around providers that expose GPS differently for the original .jpg URI.
            // Copy the selected bytes unchanged to a private .dat file, then read EXIF by file path.
            temporaryDat = java.io.File.createTempFile("gps_scan_", ".dat", cacheDir)
            val input = contentResolver.openInputStream(uri)
                ?: return PhotoGps(uri, name, null, null, null, null, "Android could not open this file.")
            input.use { source ->
                temporaryDat.outputStream().use { destination -> source.copyTo(destination) }
            }

            val exif = ExifInterface(temporaryDat.absolutePath)
            val coordinateBuffer = FloatArray(2)
            val hasCoordinates = exif.getLatLong(coordinateBuffer)
            val latitude = if (hasCoordinates) coordinateBuffer[0].toDouble() else null
            val longitude = if (hasCoordinates) coordinateBuffer[1].toDouble() else null
            val dateRaw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val date = dateRaw?.let {
                try {
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.let { parsed ->
                        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(parsed)
                    } ?: it
                } catch (_: Exception) { it }
            }
            val altitude = if (exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null) {
                exif.getAltitude(0.0)
            } else null
            val rawGps = listOf(
                "GPSLatitude" to ExifInterface.TAG_GPS_LATITUDE,
                "GPSLatitudeRef" to ExifInterface.TAG_GPS_LATITUDE_REF,
                "GPSLongitude" to ExifInterface.TAG_GPS_LONGITUDE,
                "GPSLongitudeRef" to ExifInterface.TAG_GPS_LONGITUDE_REF,
                "GPSVersionID" to ExifInterface.TAG_GPS_VERSION_ID
            ).mapNotNull { (label, tag) -> exif.getAttribute(tag)?.let { "$label=$it" } }
            val gpsTagsPresent = rawGps.isNotEmpty()
            val details = when {
                hasCoordinates -> "GPS coordinates found after scanning a temporary .dat copy of the selected file. Original photo unchanged."
                gpsTagsPresent -> "GPS tags are present but Android could not decode a coordinate pair. Raw tags: ${rawGps.joinToString("; ")}"
                else -> "No readable GPS coordinate pair in image EXIF metadata, even after scanning a temporary .dat copy."
            }
            PhotoGps(uri, name, latitude, longitude, altitude, date, details)
        } catch (e: Exception) {
            PhotoGps(uri, name, null, null, null, null, "Could not read image metadata: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            temporaryDat?.delete()
        }
    }
}

@androidx.compose.runtime.Composable
private fun GpsHelperScreen(
    readPhotos: suspend (List<Uri>) -> List<PhotoGps>,
    openMap: (Double, Double) -> Unit
) {
    var photos by remember { mutableStateOf<List<PhotoGps>>(emptyList()) }
    var isReading by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("Select photos directly from your photo library. If you already have a matching Google Photos .json sidecar, you can add it separately. Files stay on your device.")
    }
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedSidecarUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun inspectSelectedFiles() {
        val allUris = selectedImageUris + selectedSidecarUris
        if (allUris.isEmpty()) {
            status = "Select at least one photo first."
            return
        }
        isReading = true
        status = "Inspecting ${selectedImageUris.size} photo(s) and ${selectedSidecarUris.size} sidecar file(s)…"
        scope.launch {
            try {
                photos = readPhotos(allUris)
                val withGps = photos.count { it.hasGps }
                status = "Checked ${photos.size} image(s): $withGps with GPS, ${photos.size - withGps} without readable GPS. Matching selected JSON sidecars are also checked."
            } catch (e: Exception) {
                status = "Could not inspect selected files: ${e.message ?: "unknown error"}"
            } finally {
                isReading = false
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImageUris = uris
            inspectSelectedFiles()
        } else {
            status = "No photos selected."
        }
    }
    val sidecarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selectedSidecarUris = uris
        inspectSelectedFiles()
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Durga Puja GPS Helper", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text("Milestone 2 · EXIF + Google Photos sidecar diagnostics", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                photoPicker.launch(arrayOf("*/*"))
            },
            enabled = !isReading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isReading) "Reading files…" else "Browse files (JPG / DAT)")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { sidecarPicker.launch(arrayOf("application/json", "text/*")) },
            enabled = !isReading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add matching JSON sidecar (optional)")
        }
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        if (photos.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Photo results", style = MaterialTheme.typography.titleMedium)
                Text("${photos.count { it.hasGps }} / ${photos.size} GPS")
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(photos, key = { it.uri.toString() }) { photo ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(photo.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(5.dp))
                            if (photo.hasGps) {
                                Text("Latitude: ${photo.latitude}")
                                Text("Longitude: ${photo.longitude}")
                                photo.altitude?.let { Text("Altitude: ${"%.1f".format(Locale.US, it)} m") }
                                photo.capturedAt?.let { Text("Captured: $it") }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { openMap(photo.latitude!!, photo.longitude!!) }) {
                                    Text("Open in Google Maps")
                                }
                            } else {
                                Text("GPS: not found")
                                photo.capturedAt?.let { Text("Captured: $it") }
                            }
                            Text(photo.details, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
