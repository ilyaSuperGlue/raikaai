package com.raikaai.app.ui.roleplay.pages.safebooru

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL

private data class SafeBooruTag(val label: String, val value: String)
private data class SafeBooruPost(val id: Int, val previewUrl: String, val sampleUrl: String)

@Composable
fun RoleplaySafeBooruScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onSelectImage: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(emptyList<SafeBooruTag>()) }
    var activeTag by remember { mutableStateOf<SafeBooruTag?>(null) }
    var posts by remember { mutableStateOf(emptyList<SafeBooruPost>()) }
    var loading by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }

    LaunchedEffect(query) {
        val search = query.trim()
        if (search.length < 3) {
            tags = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        tags = requestTags(search)
    }

    fun searchTag(tag: SafeBooruTag) {
        activeTag = tag
        query = ""
        page = 0
        posts = emptyList()
        hasMore = true
    }

    LaunchedEffect(activeTag, page) {
        val tag = activeTag ?: return@LaunchedEffect
        loading = true
        val result = requestPosts(tag.value, page)
        posts = if (page == 0) result else posts + result
        hasMore = result.isNotEmpty()
        loading = false
    }

    Column(modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Search Image", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("tag") },
        )
        tags.forEach { tag ->
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().clickable { searchTag(tag) }.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tag.label.substringBeforeLast(" "))
                Text(tag.label.substringAfterLast(" ", ""))
            }
        }
        activeTag?.let { tag ->
            Text("result for :", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Text(tag.label, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(posts, key = { it.id }) { post ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(post.previewUrl)
                        .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED).build(),
                    contentDescription = "Select image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onSelectImage(post.sampleUrl) },
                )
            }
            if (loading) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            } else if (hasMore && posts.isNotEmpty()) {
                item {
                    LaunchedEffect(posts.size) { page += 1 }
                }
            }
        }
    }
}

private suspend fun requestTags(query: String): List<SafeBooruTag> = withContext(Dispatchers.IO) {
    runCatching {
        val json = URL("https://safebooru.org/autocomplete.php?q=${URLEncoder.encode(query, "UTF-8")}").readText()
        val array = JSONArray(json)
        (0 until minOf(array.length(), 5)).map { SafeBooruTag(array.getJSONObject(it).getString("label"), array.getJSONObject(it).getString("value")) }
    }.getOrDefault(emptyList())
}

private suspend fun requestPosts(tag: String, page: Int): List<SafeBooruPost> = withContext(Dispatchers.IO) {
    runCatching {
        val url = URL("https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1&limit=60&pid=$page&tags=${URLEncoder.encode("$tag sort:score:desc", "UTF-8")}")
        val text = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        (0 until array.length()).map { val post = array.getJSONObject(it); SafeBooruPost(post.getInt("id"), post.getString("preview_url"), post.getString("sample_url")) }
    }.getOrDefault(emptyList())
}

