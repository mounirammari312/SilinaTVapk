package com.superz.iptvplayer.ui.playlists

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.superz.iptvplayer.IPTVApp
import com.superz.iptvplayer.R
import com.superz.iptvplayer.data.PlaylistRepository
import com.superz.iptvplayer.data.db.Playlist
import com.superz.iptvplayer.ui.components.ChannelLogo
import com.superz.iptvplayer.ui.components.EmptyState
import com.superz.iptvplayer.ui.theme.AccentCyan
import com.superz.iptvplayer.ui.theme.AccentIndigo
import com.superz.iptvplayer.ui.theme.GlassSurface
import com.superz.iptvplayer.ui.theme.GlassSurfaceAlt
import com.superz.iptvplayer.ui.theme.TextMuted
import com.superz.iptvplayer.ui.theme.TextPrimary
import com.superz.iptvplayer.ui.theme.TextSecondary
import com.superz.iptvplayer.ui.theme.vuGlobalDeepSpaceBackground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PlaylistRepository.get(getApplication<IPTVApp>())

    val playlists: StateFlow<List<Playlist>> = repo.playlistsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _syncingIds = MutableStateFlow<Set<Long>>(emptySet())
    val syncingIds: StateFlow<Set<Long>> = _syncingIds.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun activate(playlist: Playlist) {
        viewModelScope.launch {
            repo.activate(playlist.id)
            _toast.value = getApplication<IPTVApp>().getString(R.string.playlist_activated)
        }
    }

    fun resync(playlist: Playlist) {
        if (_syncingIds.value.contains(playlist.id)) return
        viewModelScope.launch {
            _syncingIds.value = _syncingIds.value + playlist.id
            try {
                repo.syncPlaylist(playlist)
            } catch (e: Exception) {
                _toast.value = e.message
            } finally {
                _syncingIds.value = _syncingIds.value - playlist.id
            }
        }
    }

    fun delete(playlist: Playlist) {
        viewModelScope.launch { repo.delete(playlist) }
    }

    fun consumeToast() { _toast.value = null }
}

@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onAddPlaylist: () -> Unit,
    viewModel: PlaylistsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val syncingIds by viewModel.syncingIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    val toastMessage = viewModel.toast.collectAsStateWithLifecycle().value
    androidx.compose.runtime.LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .vuGlobalDeepSpaceBackground()
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel), tint = TextSecondary)
            }
            Text(
                stringResource(R.string.playlists_title),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onAddPlaylist) {
                Icon(Icons.Filled.Add, stringResource(R.string.add_playlist), tint = AccentCyan)
            }
        }

        if (playlists.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.no_playlists),
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                ),
                modifier = Modifier.weight(1f)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        syncing = syncingIds.contains(playlist.id),
                        onActivate = { viewModel.activate(playlist) },
                        onResync = { viewModel.resync(playlist) },
                        onDelete = { deleteTarget = playlist }
                    )
                }
            }
        }
    }

    // Delete confirmation
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_playlist), color = TextPrimary) },
            text = { Text(stringResource(R.string.delete_playlist_msg, target.name), color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete), color = com.superz.iptvplayer.ui.theme.ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel), color = TextSecondary)
                }
            },
            containerColor = GlassSurfaceAlt
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    syncing: Boolean,
    onActivate: () -> Unit,
    onResync: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurface.copy(alpha = 0.6f))
            .border(
                1.dp,
                if (playlist.isActive) AccentIndigo.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.15f),
                RoundedCornerShape(16.dp)
            )
            .clickable(enabled = !playlist.isActive) { onActivate() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Type chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (playlist.type == "XTREAM") AccentIndigo.copy(alpha = 0.25f)
                        else AccentCyan.copy(alpha = 0.18f)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    playlist.type,
                    color = if (playlist.type == "XTREAM") AccentIndigo else AccentCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                playlist.name,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (playlist.isActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AccentCyan.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        stringResource(R.string.active_badge),
                        color = AccentCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            playlistInfoLine(playlist),
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!playlist.isActive) {
                IconButton(onClick = onActivate, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.playlist_activated),
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (syncing) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = AccentCyan,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = onResync, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.resync), tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun playlistInfoLine(playlist: Playlist): String {
    val parts = mutableListOf<String>()
    parts += stringResource(R.string.channels_count, playlist.channelCount)
    if (playlist.type == "XTREAM") {
        playlist.expiryDate?.let { exp ->
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            parts += stringResource(R.string.expires, fmt.format(Date(exp * 1000)))
        }
        val active = playlist.activeConnections
        val max = playlist.maxConnections
        if (max != null && max > 0) {
            parts += stringResource(R.string.connections, active ?: 0, max)
        }
    } else {
        playlist.m3uUrl?.let { url ->
            val host = url.substringAfter("://", "").substringBefore('/')
            if (host.isNotBlank()) parts += host
        }
    }
    return parts.joinToString("  •  ")
}
