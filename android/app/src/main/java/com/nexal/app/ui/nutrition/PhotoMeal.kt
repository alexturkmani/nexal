package com.nexal.app.ui.nutrition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nexal.app.domain.model.MealSlot
import com.nexal.app.domain.model.PhotoMealResult
import com.nexal.app.ui.components.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Longest side sent to the AI. Plenty to read a plate, small enough to upload fast. */
private const val MAX_PHOTO_SIDE = 1280

/** Reads a photo, shrinks it to [MAX_PHOTO_SIDE] and returns JPEG bytes, or null if unreadable. */
internal fun loadMealJpeg(context: Context, uri: Uri): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val scale = MAX_PHOTO_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
    val bitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    } else decoded
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }
}.getOrNull()

/** Camera and gallery launchers for meal photos. Returns (takePhoto, pickPhoto). */
@Composable
internal fun rememberMealPhotoActions(
    onJpeg: (ByteArray) -> Unit,
    onError: (String) -> Unit
): Pair<() -> Unit, () -> Unit> {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun handle(uri: Uri) {
        val jpeg = loadMealJpeg(context, uri)
        if (jpeg != null) onJpeg(jpeg) else onError("Couldn't open that photo. Try another one.")
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingUri
        if (saved && uri != null) handle(uri)
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "meal_photos").apply { mkdirs() }
        val file = File(dir, "meal.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingUri = uri
        camera.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else onError("Allow camera access to snap your meal, or pick a photo instead.")
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handle(uri)
    }

    val takePhoto = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    val pickPhoto = {
        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    return takePhoto to pickPhoto
}

/** Choose a source, then review what the AI found and add it to the diary. */
@Composable
internal fun PhotoMealModal(
    state: PhotoMealState,
    isPremium: Boolean,
    freeCreditsLeft: Int?,
    initialSlot: MealSlot,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onAdd: (PhotoMealResult, MealSlot) -> Unit,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit
) {
    var slot by remember(state.result) { mutableStateOf(initialSlot) }
    FitModal(isOpen = true, onDismiss = onDismiss, title = "Snap a meal") {
        val result = state.result
        when {
            state.isLoading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Reading your meal...", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
            }
            result != null -> {
                Text(result.mealName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${result.totalMacros.calories} cal · P ${result.totalMacros.protein}g · C ${result.totalMacros.carbs}g · F ${result.totalMacros.fats}g",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    result.foods.forEach { food ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(food.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(food.servingSize, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${food.macros.calories} cal", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (result.confidence == "low" || result.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        result.notes.ifBlank { "Hard to judge from this photo. Check the portions before adding." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MealSlot.entries.forEach { option ->
                        PillChip(selected = slot == option, onClick = { slot = option }, label = { Text(option.label) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                GradientButton(text = "Add to ${slot.label}", onClick = { onAdd(result, slot) }, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                state.error?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    if (!isPremium && error.needsPremium()) {
                        GradientButton(text = "Go Premium", onClick = onUpgrade, modifier = Modifier.fillMaxWidth())
                        return@FitModal
                    }
                }
                Text(
                    "Take a photo of your plate and AI estimates the calories and macros for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isPremium) {
                    Spacer(Modifier.height(10.dp))
                    FreeCreditsNote(creditsLeft = freeCreditsLeft, onUpgrade = onUpgrade)
                }
                Spacer(Modifier.height(14.dp))
                GradientButton(text = "Take photo", onClick = onTakePhoto, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                FitButton(
                    text = "Choose from gallery",
                    onClick = onPickPhoto,
                    variant = ButtonVariant.SECONDARY,
                    icon = Icons.Default.PhotoLibrary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Top bar button that opens photo logging. */
@Composable
internal fun SnapMealButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.PhotoCamera, contentDescription = "Snap a meal", tint = MaterialTheme.colorScheme.primary)
    }
}
