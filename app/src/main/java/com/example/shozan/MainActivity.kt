package com.example.shozan

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.shozan.ui.theme.ShozanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val duration: Long = 0,
    var albumArt: Bitmap? = null
)

@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Calculate screen height in pixels
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val hiddenOffset = screenHeightPx - with(density) { 300.dp.toPx() } // Peek showing controls and progress bar

    var hasPermission by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var currentSongIndex by rememberSaveable { mutableStateOf(0) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var currentProgress by rememberSaveable { mutableStateOf(0f) }
    var currentPosition by rememberSaveable { mutableStateOf(0) }
    var duration by rememberSaveable { mutableStateOf(0) }
    var listOffsetY by remember { mutableStateOf(hiddenOffset) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val animatedListOffset = remember { Animatable(hiddenOffset) }
    val animatedButtonScale = remember { Animatable(1f) }

    val lazyListState = rememberLazyListState()
    val refreshRotation = remember { Animatable(0f) }

    // Function to scan for music
    fun scanForMusic() {
        coroutineScope.launch {
            isScanning = true
            refreshRotation.animateTo(
                targetValue = 360f,
                animationSpec = tween(durationMillis = 800, easing = LinearEasing)
            )
            refreshRotation.snapTo(0f)

            withContext(Dispatchers.IO) {
                // Trigger media scan
                try {
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(musicDir.absolutePath, downloadDir.absolutePath),
                        null
                    ) { _, _ -> }

                    delay(1000) // Give MediaStore time to update
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val newPlaylist = loadMusicFromDevice(context)
                withContext(Dispatchers.Main) {
                    playlist = newPlaylist
                    isScanning = false
                }
            }
        }
    }

    val mediaPlayer = remember { MediaPlayer() }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            playlist = loadMusicFromDevice(context)
        }
    }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            playlist = loadMusicFromDevice(context)
        } else {
            launcher.launch(permission)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer.release()
        }
    }

    // Load album art for current song
    LaunchedEffect(currentSongIndex, playlist.size) {
        if (playlist.isNotEmpty() && currentSongIndex < playlist.size) {
            val song = playlist[currentSongIndex]
            if (song.albumArt == null) {
                val art = loadAlbumArt(context, song.uri)
                playlist = playlist.toMutableList().apply {
                    this[currentSongIndex] = song.copy(albumArt = art)
                }
            }
        }
    }

    // Preload album art for visible songs in the list
    LaunchedEffect(lazyListState.firstVisibleItemIndex, playlist.size) {
        if (playlist.isEmpty()) return@LaunchedEffect

        val startIndex = lazyListState.firstVisibleItemIndex
        val endIndex = (startIndex + 10).coerceAtMost(playlist.size - 1)

        for (i in startIndex..endIndex) {
            if (playlist[i].albumArt == null) {
                launch {
                    val art = loadAlbumArt(context, playlist[i].uri)
                    playlist = playlist.toMutableList().apply {
                        this[i] = this[i].copy(albumArt = art)
                    }
                }
            }
        }
    }

    LaunchedEffect(listOffsetY) {
        animatedListOffset.animateTo(
            targetValue = listOffsetY,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(100)
            if (mediaPlayer.isPlaying) {
                currentPosition = mediaPlayer.currentPosition
                duration = mediaPlayer.duration
                currentProgress = currentPosition.toFloat() / duration.toFloat()
            }
        }
    }

    fun playSong(index: Int) {
        if (playlist.isEmpty()) return
        currentSongIndex = index
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(context, playlist[index].uri)
            mediaPlayer.prepare()
            mediaPlayer.start()
            isPlaying = true
            duration = mediaPlayer.duration
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun togglePlayPause() {
        if (playlist.isEmpty()) return
        coroutineScope.launch {
            animatedButtonScale.animateTo(
                targetValue = 0.85f,
                animationSpec = tween(100, easing = FastOutSlowInEasing)
            )
            animatedButtonScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(100, easing = FastOutSlowInEasing)
            )
        }
        if (isPlaying) {
            mediaPlayer.pause()
            isPlaying = false
        } else {
            if (currentPosition > 0) {
                mediaPlayer.start()
            } else {
                playSong(currentSongIndex)
            }
            isPlaying = true
        }
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        val nextIndex = (currentSongIndex + 1) % playlist.size
        coroutineScope.launch {
            animatedButtonScale.animateTo(0.9f, tween(100))
            animatedButtonScale.animateTo(1f, tween(100))
        }
        playSong(nextIndex)
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        val prevIndex = if (currentSongIndex - 1 < 0) playlist.size - 1 else currentSongIndex - 1
        coroutineScope.launch {
            animatedButtonScale.animateTo(0.9f, tween(100))
            animatedButtonScale.animateTo(1f, tween(100))
        }
        playSong(prevIndex)
    }

    // Set up completion listener for auto-play next
    LaunchedEffect(Unit) {
        mediaPlayer.setOnCompletionListener {
            if (playlist.isNotEmpty()) {
                playNext()
            }
        }
    }

    val currentSong = if (playlist.isNotEmpty()) playlist[currentSongIndex] else null
    val midPoint = screenHeightPx / 2

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E11))
    ) {
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Storage permission needed",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(permission) }) {
                    Text("Grant Permission")
                }
            }
        } else if (playlist.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No music found on device",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = currentSongIndex,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(400)) +
                                slideInHorizontally(animationSpec = tween(400)) { it / 3 })
                            .togetherWith(
                                fadeOut(animationSpec = tween(400)) +
                                        slideOutHorizontally(animationSpec = tween(400)) { -it / 3 }
                            )
                    },
                    label = "album-art"
                ) { index ->
                    val song = if (playlist.isNotEmpty() && index < playlist.size) playlist[index] else null

                    if (song?.albumArt != null) {
                        Image(
                            bitmap = song.albumArt!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF2A2A2A))
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF0E0E11).copy(alpha = 0.9f)
                                ),
                                startY = 200f
                            )
                        )
                )

                // Top gradient for text visibility
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF000000).copy(alpha = 0.7f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .padding(top = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AnimatedContent(
                                targetState = currentSongIndex,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(300)) +
                                            slideInHorizontally(animationSpec = tween(300)) { it / 4 })
                                        .togetherWith(
                                            fadeOut(animationSpec = tween(300)) +
                                                    slideOutHorizontally(animationSpec = tween(300)) { -it / 4 }
                                        )
                                },
                                label = "song-info"
                            ) { index ->
                                val song = if (playlist.isNotEmpty() && index < playlist.size) playlist[index] else null
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = song?.title ?: "No Song",
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song?.artist ?: "Unknown Artist",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Scanning indicator
                    if (isScanning) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFF8B7CFF),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Scanning for music...",
                                color = Color(0xFF8B7CFF),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.dp)
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(0, (animatedListOffset.value - 6).roundToInt()) }
                    .background(Color(0xFF1A1A1A))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                            val newPosition = (newProgress * duration).toInt()
                            mediaPlayer.seekTo(newPosition)
                            currentPosition = newPosition
                            currentProgress = newProgress
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(currentProgress)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF8B7CFF),
                                    Color(0xFFB794F6)
                                )
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, animatedListOffset.value.roundToInt()) }
                    .padding(top = 80.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragOffsetY = 0f
                                coroutineScope.launch {
                                    animatedListOffset.stop()
                                }
                            },
                            onDragEnd = {
                                val totalOffset = animatedListOffset.value + dragOffsetY
                                val threshold = with(density) { 150.dp.toPx() }

                                // Snap to current drag position before animating
                                coroutineScope.launch {
                                    animatedListOffset.snapTo(totalOffset)
                                }

                                // Determine target position
                                if (listOffsetY > midPoint && totalOffset < (hiddenOffset - threshold)) {
                                    listOffsetY = 0f
                                }
                                else if (listOffsetY < midPoint && totalOffset > threshold) {
                                    listOffsetY = hiddenOffset
                                }
                                else {
                                    listOffsetY = if (totalOffset < midPoint) 0f else hiddenOffset
                                }
                                dragOffsetY = 0f
                            },
                            onVerticalDrag = { _, dragAmount ->
                                val newOffset = dragOffsetY + dragAmount
                                val totalOffset = animatedListOffset.value + newOffset
                                if (totalOffset >= 0 && totalOffset <= screenHeightPx) {
                                    dragOffsetY = newOffset
                                }
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                        .background(Color.Transparent)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "Previous",
                            tint = Color(0xFF8B7CFF),
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { playPrevious() }
                        )

                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .graphicsLayer {
                                    scaleX = animatedButtonScale.value
                                    scaleY = animatedButtonScale.value
                                }
                                .background(Color(0xFF8B7CFF), CircleShape)
                                .clickable { togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = isPlaying,
                                transitionSpec = {
                                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                                },
                                label = "play-pause"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Next",
                            tint = Color(0xFF8B7CFF),
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { playNext() }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(Color(0xFF1A1A1A))
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                                    val newPosition = (newProgress * duration).toInt()
                                    mediaPlayer.seekTo(newPosition)
                                    currentPosition = newPosition
                                    currentProgress = newProgress
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(currentProgress)
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF8B7CFF),
                                            Color(0xFFB794F6)
                                        )
                                    )
                                )
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0E0E11))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PLAYING",
                            color = Color(0xFF8B7CFF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Your Library (${playlist.size} songs)",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = { scanForMusic() },
                            modifier = Modifier.size(32.dp),
                            enabled = !isScanning
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh library",
                                tint = if (isScanning) Color(0xFF8B7CFF).copy(alpha = 0.5f) else Color(0xFF8B7CFF),
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        rotationZ = refreshRotation.value
                                    }
                            )
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0E0E11).copy(alpha = 0.7f))
                                .blur(20.dp)
                        )

                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(playlist.size) { index ->
                                SongItem(
                                    song = playlist[index],
                                    isCurrentSong = index == currentSongIndex,
                                    onClick = {
                                        playSong(index)
                                        listOffsetY = hiddenOffset
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongItem(song: Song, isCurrentSong: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrentSong) Color(0xFF8B7CFF).copy(alpha = 0.2f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (song.albumArt != null) {
            Image(
                bitmap = song.albumArt!!.asImageBitmap(),
                contentDescription = "Album Art",
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = if (isCurrentSong) Color(0xFF8B7CFF) else Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "More options",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }
}

suspend fun loadAlbumArt(context: android.content.Context, uri: Uri): Bitmap? {
    return withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val art = retriever.embeddedPicture
            retriever.release()
            art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) {
            null
        }
    }
}

fun loadMusicFromDevice(context: android.content.Context): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION
    )

    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    context.contentResolver.query(
        collection,
        projection,
        selection,
        null,
        "${MediaStore.Audio.Media.TITLE} ASC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn)
            val artist = cursor.getString(artistColumn)
            val duration = cursor.getLong(durationColumn)

            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id
            )

            songs.add(Song(id, title, artist ?: "Unknown Artist", uri, duration))
        }
    }

    return songs
}

fun formatTime(millis: Int): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / 1000) / 60
    return String.format("%02d:%02d", minutes, seconds)
}

@Composable
fun ShozanApp() {
    PlayerScreen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShozanTheme {
                ShozanApp()
            }
        }
    }
}