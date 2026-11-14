package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.data.AlbumEntity
import com.example.core.data.AlbumMediaCrossRef
import com.example.core.data.MediaFaceTagEntity
import com.example.core.data.MediaItemEntity
import com.example.core.data.PairedServerEntity
import com.example.core.data.PersonClusterEntity
import com.example.core.model.MediaCategory
import com.example.core.model.PersonCategory
import com.example.ui.components.MediaThumbnailCard
import java.io.File

private sealed interface CollectionsSubPage {
    data object Hub : CollectionsSubPage
    data object PeopleAndPets : CollectionsSubPage
    data class PersonDetail(val personId: String) : CollectionsSubPage
    data class CategoryGrid(val title: String, val category: MediaCategory) : CollectionsSubPage
    data class AlbumDetail(val album: AlbumEntity) : CollectionsSubPage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    allNonTrashed: List<MediaItemEntity>,
    favorites: List<MediaItemEntity>,
    videos: List<MediaItemEntity>,
    screenshots: List<MediaItemEntity>,
    documents: List<MediaItemEntity>,
    archived: List<MediaItemEntity>,
    trashed: List<MediaItemEntity>,
    albums: List<AlbumEntity>,
    albumCrossRefs: List<AlbumMediaCrossRef>,
    people: List<PersonClusterEntity>,
    faceTags: List<MediaFaceTagEntity>,
    activeServer: PairedServerEntity?,
    onOpenMedia: (MediaItemEntity) -> Unit,
    onCreateAlbum: (String) -> Unit,
    onDeleteAlbum: (String) -> Unit,
    onRunFaceScan: () -> Unit,
    onRenamePerson: (String, String) -> Unit,
    onUpdatePersonCategory: (String, PersonCategory) -> Unit,
    onTagManualPerson: (String, PersonCategory, MediaItemEntity) -> Unit,
    onRestoreFromTrash: (MediaItemEntity) -> Unit,
    onDeletePermanently: (MediaItemEntity) -> Unit
) {
    var subPage by remember { mutableStateOf<CollectionsSubPage>(CollectionsSubPage.Hub) }
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }

    when (val page = subPage) {
        CollectionsSubPage.Hub -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Collections", style = MaterialTheme.typography.headlineSmall) },
                        actions = {
                            IconButton(onClick = { showCreateAlbumDialog = true }) {
                                Icon(Icons.Rounded.Add, contentDescription = "Create album")
                            }
                        }
                    )
                }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Quick Utilities Grid (2 rows of 3)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                QuickCollectionTile(
                                    icon = Icons.Rounded.Favorite,
                                    title = "Favorites",
                                    count = favorites.size,
                                    modifier = Modifier.weight(1f),
                                    onClick = { subPage = CollectionsSubPage.CategoryGrid("Favorites", MediaCategory.FAVORITES) }
                                )
                                QuickCollectionTile(
                                    icon = Icons.Rounded.Videocam,
                                    title = "Videos",
                                    count = videos.size,
                                    modifier = Modifier.weight(1f),
                                    onClick = { subPage = CollectionsSubPage.CategoryGrid("Videos", MediaCategory.VIDEOS) }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                QuickCollectionTile(
                                    icon = Icons.Rounded.Screenshot,
                                    title = "Screenshots",
                                    count = screenshots.size,
                                    modifier = Modifier.weight(1f),
                                    onClick = { subPage = CollectionsSubPage.CategoryGrid("Screenshots", MediaCategory.SCREENSHOTS) }
                                )
                                QuickCollectionTile(
                                    icon = Icons.Rounded.Description,
                                    title = "Documents",
                                    count = documents.size,
                                    modifier = Modifier.weight(1f),
                                    onClick = { subPage = CollectionsSubPage.CategoryGrid("Documents", MediaCategory.DOCUMENTS) }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                QuickCollectionTile(
                                    icon = Icons.Rounded.Archive,
                                    title = "Archived",
                                    count = archived.size,
                                    modifier = Modifier.weight(1f),
                                    onClick = { subPage = CollectionsSubPage.CategoryGrid("Archived", MediaCategory.ARCHIVED) }
                                )
                                QuickCollectionTile(
                                    icon = Icons.Rounded.Delete,
                                    title = "Trash",
                                    count = trashed.size,
                                    modifier = Modifier.weight(1f),
                                    onClick = { subPage = CollectionsSubPage.CategoryGrid("Recently Deleted", MediaCategory.TRASH) }
                                )
                            }
                        }
                    }

                    // People & Pets Preview Card (Google Photos style)
                    item {
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { subPage = CollectionsSubPage.PeopleAndPets }
                                .testTag("people_and_pets_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Rounded.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text("People & Pets", style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(
                                        text = "View all",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (people.isEmpty()) {
                                    Text(
                                        text = "Tap to run 100% on-device face grouping or tag people & pets.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        items(people.take(10), key = { it.id }) { person ->
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .clickable { subPage = CollectionsSubPage.PersonDetail(person.id) }
                                            ) {
                                                PersonAvatarCircle(person = person, sizeDp = 64)
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = person.name,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Albums Section
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Albums", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { showCreateAlbumDialog = true }) {
                                Text("+ New Album")
                            }
                        }
                    }

                    if (albums.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Rounded.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = "No albums yet. Create an album to organize trips, family events, or highlights.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(albums, key = { it.id }) { album ->
                            val count = albumCrossRefs.count { it.albumId == album.id }
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { subPage = CollectionsSubPage.AlbumDetail(album) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(52.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.PhotoAlbum,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(album.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "$count item(s)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        CollectionsSubPage.PeopleAndPets -> {
            PeopleAndPetsScreen(
                people = people,
                sampleMedia = allNonTrashed.firstOrNull { !it.isVideo },
                onBack = { subPage = CollectionsSubPage.Hub },
                onSelectPerson = { subPage = CollectionsSubPage.PersonDetail(it.id) },
                onRunFaceScan = onRunFaceScan,
                onTagManualPerson = onTagManualPerson
            )
        }

        is CollectionsSubPage.PersonDetail -> {
            val person = people.firstOrNull { it.id == page.personId }
            val mediaIds = faceTags.filter { it.personId == page.personId }.map { it.mediaId }.toSet()
            val items = allNonTrashed.filter { it.id in mediaIds }
            PersonDetailScreen(
                person = person,
                items = items,
                activeServer = activeServer,
                onBack = { subPage = CollectionsSubPage.PeopleAndPets },
                onOpenMedia = onOpenMedia,
                onRename = { newName -> person?.let { onRenamePerson(it.id, newName) } },
                onSwitchCategory = { cat -> person?.let { onUpdatePersonCategory(it.id, cat) } }
            )
        }

        is CollectionsSubPage.CategoryGrid -> {
            val items = when (page.category) {
                MediaCategory.FAVORITES -> favorites
                MediaCategory.VIDEOS -> videos
                MediaCategory.SCREENSHOTS -> screenshots
                MediaCategory.DOCUMENTS -> documents
                MediaCategory.ARCHIVED -> archived
                MediaCategory.TRASH -> trashed
                MediaCategory.ALL -> allNonTrashed
            }
            CategoryMediaScreen(
                title = page.title,
                items = items,
                isTrash = page.category == MediaCategory.TRASH,
                activeServer = activeServer,
                onBack = { subPage = CollectionsSubPage.Hub },
                onOpenMedia = onOpenMedia,
                onRestoreFromTrash = onRestoreFromTrash,
                onDeletePermanently = onDeletePermanently
            )
        }

        is CollectionsSubPage.AlbumDetail -> {
            val mediaIds = albumCrossRefs.filter { it.albumId == page.album.id }.map { it.mediaId }.toSet()
            val items = allNonTrashed.filter { it.id in mediaIds }
            CategoryMediaScreen(
                title = page.album.name,
                items = items,
                isTrash = false,
                activeServer = activeServer,
                onBack = { subPage = CollectionsSubPage.Hub },
                onOpenMedia = onOpenMedia,
                onRestoreFromTrash = {},
                onDeletePermanently = {},
                onDeleteAlbum = {
                    onDeleteAlbum(page.album.id)
                    subPage = CollectionsSubPage.Hub
                }
            )
        }
    }

    if (showCreateAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showCreateAlbumDialog = false },
            title = { Text("New Album") },
            text = {
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = { newAlbumName = it },
                    label = { Text("Album title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onCreateAlbum(newAlbumName)
                    newAlbumName = ""
                    showCreateAlbumDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAlbumDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun QuickCollectionTile(
    icon: ImageVector,
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleAndPetsScreen(
    people: List<PersonClusterEntity>,
    sampleMedia: MediaItemEntity?,
    onBack: () -> Unit,
    onSelectPerson: (PersonClusterEntity) -> Unit,
    onRunFaceScan: () -> Unit,
    onTagManualPerson: (String, PersonCategory, MediaItemEntity) -> Unit
) {
    BackHandler(onBack = onBack)
    var categoryFilter by remember { mutableStateOf<PersonCategory?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf(PersonCategory.PEOPLE) }

    val filtered = remember(people, categoryFilter) {
        if (categoryFilter == null) people else people.filter { it.category == categoryFilter!!.name }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("People & Pets") },
                actions = {
                    if (sampleMedia != null) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = "Tag person or pet")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "On-device face grouping only. Facial data never leaves your phone or server.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = categoryFilter == null,
                            onClick = { categoryFilter = null },
                            label = { Text("All (${people.size})") }
                        )
                        FilterChip(
                            selected = categoryFilter == PersonCategory.PEOPLE,
                            onClick = { categoryFilter = PersonCategory.PEOPLE },
                            leadingIcon = { Icon(Icons.Rounded.Face, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("People") }
                        )
                        FilterChip(
                            selected = categoryFilter == PersonCategory.PETS,
                            onClick = { categoryFilter = PersonCategory.PETS },
                            leadingIcon = { Icon(Icons.Rounded.Pets, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("Pets") }
                        )
                    }

                    OutlinedButton(
                        onClick = onRunFaceScan,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan Library On-Device for Faces")
                    }
                }
            }

            items(filtered, key = { it.id }) { person ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelectPerson(person) }
                        .padding(4.dp)
                ) {
                    PersonAvatarCircle(person = person, sizeDp = 88)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "${person.faceCount} photo(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAddDialog && sampleMedia != null) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Person or Pet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newCategory == PersonCategory.PEOPLE,
                            onClick = { newCategory = PersonCategory.PEOPLE },
                            label = { Text("Person") }
                        )
                        FilterChip(
                            selected = newCategory == PersonCategory.PETS,
                            onClick = { newCategory = PersonCategory.PETS },
                            label = { Text("Pet") }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onTagManualPerson(newName, newCategory, sampleMedia)
                    newName = ""
                    showAddDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PersonAvatarCircle(person: PersonClusterEntity, sizeDp: Int) {
    val file = person.avatarPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = person.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(sizeDp.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (person.category == PersonCategory.PETS.name) Icons.Rounded.Pets else Icons.Rounded.Face,
                    contentDescription = person.name,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size((sizeDp / 2).dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonDetailScreen(
    person: PersonClusterEntity?,
    items: List<MediaItemEntity>,
    activeServer: PairedServerEntity?,
    onBack: () -> Unit,
    onOpenMedia: (MediaItemEntity) -> Unit,
    onRename: (String) -> Unit,
    onSwitchCategory: (PersonCategory) -> Unit
) {
    BackHandler(onBack = onBack)
    var showRenameDialog by remember { mutableStateOf(false) }
    var nameInput by remember(person) { mutableStateOf(person?.name.orEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(person?.name ?: "Person") },
                actions = {
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Rename")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (person != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = person.category == PersonCategory.PEOPLE.name,
                            onClick = { onSwitchCategory(PersonCategory.PEOPLE) },
                            label = { Text("Person") }
                        )
                        FilterChip(
                            selected = person.category == PersonCategory.PETS.name,
                            onClick = { onSwitchCategory(PersonCategory.PETS) },
                            label = { Text("Pet") }
                        )
                    }
                }
            }
            items(items, key = { it.id }) { media ->
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

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Name this face") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRename(nameInput)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryMediaScreen(
    title: String,
    items: List<MediaItemEntity>,
    isTrash: Boolean,
    activeServer: PairedServerEntity?,
    onBack: () -> Unit,
    onOpenMedia: (MediaItemEntity) -> Unit,
    onRestoreFromTrash: (MediaItemEntity) -> Unit,
    onDeletePermanently: (MediaItemEntity) -> Unit,
    onDeleteAlbum: (() -> Unit)? = null
) {
    BackHandler(onBack = onBack)
    var showDeleteAlbumConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("$title (${items.size})") },
                actions = {
                    if (onDeleteAlbum != null) {
                        IconButton(onClick = { showDeleteAlbumConfirm = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete album")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Nothing in $title yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items, key = { it.id }) { media ->
                    Box {
                        MediaThumbnailCard(
                            item = media,
                            activeServer = activeServer,
                            isSelected = false,
                            selectionMode = false,
                            onClick = { onOpenMedia(media) },
                            onLongClick = {}
                        )
                        if (isTrash) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                IconButton(onClick = { onRestoreFromTrash(media) }) {
                                    Icon(Icons.Rounded.RestoreFromTrash, contentDescription = "Restore")
                                }
                                IconButton(onClick = { onDeletePermanently(media) }) {
                                    Icon(Icons.Rounded.DeleteForever, contentDescription = "Delete permanently")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAlbumConfirm && onDeleteAlbum != null) {
        AlertDialog(
            onDismissRequest = { showDeleteAlbumConfirm = false },
            title = { Text("Delete album?") },
            text = { Text("Photos and videos inside this album will remain in your library.") },
            confirmButton = {
                Button(onClick = {
                    showDeleteAlbumConfirm = false
                    onDeleteAlbum()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAlbumConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
