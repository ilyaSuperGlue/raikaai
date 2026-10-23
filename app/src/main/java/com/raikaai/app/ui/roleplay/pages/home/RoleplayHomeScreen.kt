package com.raikaai.app.ui.roleplay.pages.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.raikaai.app.util.orFallback

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoleplayHomeScreen(modifier: Modifier = Modifier, onOpenChat: (RoleplayCharacter) -> Unit = {}, onOpenSearch: () -> Unit = {}, home: RoleplayHomeViewModel = viewModel()) {
    fun openCharacter(character: RoleplayCharacter) {
        home.incrementCharacterView(character.id)
        onOpenChat(character)
    }
    val state by home.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    val characters = state.characters.filter { it.name.contains(query, true) || it.description.orEmpty().contains(query, true) }

    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onOpenSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("find character", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Trending", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(state.trending, key = { it.id }) { CharacterCard(it, Modifier.width(155.dp), ::openCharacter) } }
            Spacer(Modifier.height(12.dp))
        }
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    items(state.categories, key = { it.id }) { category ->
                        FilterChip(state.selectedCategoryId == category.id, { home.selectCategory(category.id) }, label = { Text(category.name) })
                    }
                }
            }
        }
        items(characters.chunked(2), key = { it.firstOrNull()?.id ?: 0 }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                row.forEach { character -> Box(Modifier.weight(1f)) { CharacterCard(character, Modifier.fillMaxWidth(), ::openCharacter) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { if (state.hasMore) LaunchedEffect(characters.size) { home.loadMore() } }
        if (state.loading || state.loadingMore) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun CharacterCard(character: RoleplayCharacter, modifier: Modifier = Modifier, onOpenChat: (RoleplayCharacter) -> Unit = {}) {
    Card(onClick = { onOpenChat(character) }, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Column {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(character.avatarUrl).diskCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.ENABLED).build(), contentDescription = character.name, modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Text(character.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(character.description.orFallback(character.greeting.orEmpty()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
