package com.barshan94.durgapujagps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GpsHelperScreen(
                        readPhotos = { uris -> withContext(Dispatchers.IO) { uris.map { readPhoto(it) } } },
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

    private fun readPhoto(uri: Uri): PhotoGps {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected photo"
        return try {
            val stream = contentResolver.openInputStream(uri)
                ?: return PhotoGps(uri, name, null, null, null, null, "Android could not open this photo.")
            val exif = stream.use { ExifInterface(it) }
            val coordinateBuffer = FloatArray(2)
            val hasCoordinates = exif.getLatLong(coordinateBuffer)
            val coords = if (hasCoordinates) coordinateBuffer else null
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
            PhotoGps(
                uri, name, coords?.getOrNull(0)?.toDouble(), coords?.getOrNull(1)?.toDouble(), altitude, date,
                if (coords != null) "GPS coordinates found in image metadata."
                else "No readable GPS coordinates in the image EXIF metadata."
            )
        } catch (e: Exception) {
            PhotoGps(uri, name, null, null, null, null, "Could not read metadata: ${e.message ?: e.javaClass.simpleName}")
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
    var status by remember { mutableStateOf("Choose original photos to inspect their embedded GPS metadata. Photos stay on your device.") }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) {
            status = "No photos selected."
        } else {
            isReading = true
            status = "Reading metadata from ${uris.size} photo(s)…"
            scope.launch {
                try {
                    photos = readPhotos(uris)
                    val withGps = photos.count { it.hasGps }
                    status = "Checked ${photos.size} photo(s): $withGps with GPS, ${photos.size - withGps} without readable GPS."
                } catch (e: Exception) {
                    status = "Could not inspect photos: ${e.message ?: "unknown error"}"
                } finally {
                    isReading = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Durga Puja GPS Helper", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text("Milestone 1 · On-device photo diagnostics", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !isReading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isReading) "Reading photos…" else "Select photos")
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
