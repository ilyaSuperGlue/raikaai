package com.raikaai.app.ui.roleplay.pages.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.google.gson.JsonParser
import com.raikaai.app.data.RolePlayConstants
import com.raikaai.app.ui.roleplay.pages.home.RoleplayCharacter
import com.raikaai.app.ui.roleplay.pages.home.RoleplayHomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
fun RoleplaySearchScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenChat: (RoleplayCharacter) -> Unit = {},
    home: RoleplayHomeViewModel = viewModel(),
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<RoleplayCharacter>()) }
    var loading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun search() {
        val text = query.trim()
        if (text.isBlank()) return
        scope.launch {
            loading = true
            hasSearched = true
            results = searchCharacters(text)
            loading = false
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Search characters", style = MaterialTheme.typography.headlineSmall)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            placeholder = { Text("find character") },
            trailingIcon = {
                IconButton(onClick = ::search, enabled = !loading) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else if (hasSearched && results.isEmpty()) {
            Text("No characters found", modifier = Modifier.padding(top = 20.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.id }) { character ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            home.incrementCharacterView(character.id)
                            onOpenChat(character)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(character.avatarUrl)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = character.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(character.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private suspend fun searchCharacters(query: String): List<RoleplayCharacter> = withContext(Dispatchers.IO) {
    runCatching {
        val encoded = android.net.Uri.encode("*$query*")
        val url = RolePlayConstants.BASE_URL +
            "characters?select=id,name,slug,description,greeting,system_prompt,avatar_url,category_id,view" +
            "&name=ilike.$encoded&order=name.asc&limit=50"
        OkHttpClient().newCall(
            Request.Builder().url(url).header("apikey", RolePlayConstants.PUBLIC_KEY).build(),
        ).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray.mapNotNull { value ->
                val o = value.asJsonObject
                val id = o.get("id")?.asInt ?: return@mapNotNull null
                val name = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RoleplayCharacter(
                    id = id,
                    name = name,
                    slug = o.get("slug")?.asString.orEmpty(),
                    description = o.get("description")?.takeUnless { it.isJsonNull }?.asString,
                    greeting = o.get("greeting")?.takeUnless { it.isJsonNull }?.asString,
                    systemPrompt = o.get("system_prompt")?.takeUnless { it.isJsonNull }?.asString,
                    avatarUrl = o.get("avatar_url")?.takeUnless { it.isJsonNull }?.asString,
                    categoryId = o.get("category_id")?.asInt ?: 9999,
                    view = o.get("view")?.asInt ?: 0,
                )
            }
        }
    }.getOrDefault(emptyList())
}

