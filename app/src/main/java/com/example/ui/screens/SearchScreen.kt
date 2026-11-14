package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.data.AlbumEntity
import com.example.core.data.AlbumMediaCrossRef
import com.example.core.data.MediaFaceTagEntity
import com.example.core.data.MediaItemEntity
import com.example.core.data.PairedServerEntity
import com.example.core.data.PersonClusterEntity
import com.example.core.media.OnDeviceSearchEngine
import com.example.core.media.OnDeviceSearchEngine.SearchFilterType
import com.example.ui.components.MediaThumbnailCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    mediaItems: List<MediaItemEntity>,
    albums: List<AlbumEntity>,
    albumCrossRefs: List<AlbumMediaCrossRef>,
    people: List<PersonClusterEntity>,
    faceTags: List<MediaFaceTagEntity>,
    activeServer: PairedServerEntity?,
    aiSearchEnabled: Boolean,
    onToggleAiSearch: (Boolean) -> Unit,
    onOpenMedia: (MediaItemEntity) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf(SearchFilterType.ALL) }

    val peopleNamesByMediaId = remember(people, faceTags) {
        val nameMap = people.associateBy({ it.id }, { it.name })
        faceTags.groupBy { it.mediaId }.mapValues { (_, tags) ->
            tags.mapNotNull { nameMap[it.personId] }
        }
    }

    val albumNamesByMediaId = remember(albums, albumCrossRefs) {
        val nameMap = albums.associateBy({ it.id }, { it.name })
        albumCrossRefs.groupBy { it.mediaId }.mapValues { (_, refs) ->
            refs.mapNotNull { nameMap[it.albumId] }
        }
    }

    val results = remember(mediaItems, query, filterType, peopleNamesByMediaId, albumNamesByMediaId, aiSearchEnabled) {
        OnDeviceSearchEngine.filterMedia(
            items = mediaItems,
            query = query,
            filterType = filterType,
            peopleNamesByMediaId = peopleNamesByMediaId,
            albumNamesByMediaId = albumNamesByMediaId,
            aiSemanticEnabled = aiSearchEnabled
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Search", style = MaterialTheme.typography.headlineSmall) })
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search date, filename, person, album, location…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_query_input")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SearchFilterType.entries.forEach { type ->
                            FilterChip(
                                selected = filterType == type,
                                onClick = { filterType = type },
                                label = {
                                    Text(
                                        type.name.lowercase().replaceFirstChar { it.uppercase() }
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("On-Device Smart AI Search", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = aiSearchEnabled, onCheckedChange = onToggleAiSearch)
                    }

                    Text(
                        text = "${results.size} matching item(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            items(results, key = { it.id }) { media ->
                MediaThumbnailCard(
                    item = media,
                    activeServer = activeServer,
                    isSelected = false,
                    selectionMode = false,
                    onClick = { onOpenMedia(media) },
                    onLongClick = {}
                )
            }
        }
    }
}
