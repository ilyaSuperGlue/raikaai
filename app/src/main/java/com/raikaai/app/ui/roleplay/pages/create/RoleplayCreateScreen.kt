package com.raikaai.app.ui.roleplay.pages.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.raikaai.app.ui.chat.ChatViewModel
import com.raikaai.app.data.RolePlayConstants
import com.raikaai.app.ui.roleplay.pages.home.RoleplayCharacter
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Composable
fun RoleplayCreateScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel? = null,
    onSearchImage: () -> Unit = {},
    selectedImageUrl: String = "",
    onPublished: () -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var greeting by rememberSaveable { mutableStateOf("") }
    var imageUrl by rememberSaveable { mutableStateOf("") }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var isPublishing by remember { mutableStateOf(false) }
    var publishError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedImageUrl) {
        if (selectedImageUrl.isNotBlank()) imageUrl = selectedImageUrl
    }

    val canContinue = name.isNotBlank() &&
        description.isNotBlank() &&
        greeting.isNotBlank() &&
        imageUrl.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Create character",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )

        CreateInput("Name", name, { name = it })
        CreateInput("Description", description, { description = it }, multiline = true)
        CreateInput("Greeting", greeting, { greeting = it }, multiline = true)
        CreateInput("Image url", imageUrl, { imageUrl = it })

        OutlinedButton(
            onClick = onSearchImage,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) {
            Text("Search image?")
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showPreview = true },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }

    if (showPreview) {
        val systemPrompt = "You are $name. Stay in character and respond consistently with this character description: $description"
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = {
                Row {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = name,
                        modifier = Modifier.size(70.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column { 
                        Text(name, style = MaterialTheme.typography.headlineSmall)
                        Text(description, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Greeting", style = MaterialTheme.typography.titleMedium)
                    Text(greeting, style = MaterialTheme.typography.bodyMedium)
                    Text("System Prompt", style = MaterialTheme.typography.titleMedium)
                    Text(systemPrompt, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(
                    enabled = !isPublishing,
                    onClick = {
                        scope.launch {
                            isPublishing = true
                            publishError = null
                            val character = publishCharacter(name, description, greeting, systemPrompt, imageUrl)
                            if (character != null) {
                                viewModel?.saveRoleplayCharacterToHistory(
                                    com.raikaai.app.ui.chat.PersonaPreset(
                                        id = "roleplay_${character.id}",
                                        name = character.name,
                                        description = character.description.orEmpty(),
                                        greeting = character.greeting.orEmpty(),
                                        systemPrompt = character.systemPrompt.orEmpty(),
                                        avatarUri = character.avatarUrl.orEmpty(),
                                    ),
                                )
                                name = ""
                                description = ""
                                greeting = ""
                                imageUrl = ""
                                showPreview = false
                                onPublished()
                            } else {
                                publishError = "Unable to publish character"
                            }
                            isPublishing = false
                        }
                    },
                ) { Text(if (isPublishing) "Publishing…" else "Publish Character") }
            },
            dismissButton = {
                TextButton(onClick = { showPreview = false }) { Text("Cancel") }
            },
        )
    }
    publishError?.let { error ->
        AlertDialog(
            onDismissRequest = { publishError = null },
            title = { Text("Publish failed") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { publishError = null }) { Text("OK") } },
        )
    }
}

private suspend fun publishCharacter(
    name: String,
    description: String,
    greeting: String,
    systemPrompt: String,
    avatarUrl: String,
): RoleplayCharacter? = withContext(Dispatchers.IO) {
    runCatching {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val payload = Gson().toJson(
            mapOf(
                "name" to name.trim(),
                "slug" to slug,
                "description" to description.trim(),
                "greeting" to greeting.trim(),
                "system_prompt" to systemPrompt.trim(),
                "avatar_url" to avatarUrl.trim().ifBlank { null },
            ),
        )
        OkHttpClient().newCall(
            Request.Builder()
                .url("${RolePlayConstants.BASE_URL}characters")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("apikey", RolePlayConstants.PUBLIC_KEY)
                .header("Prefer", "return=representation")
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JsonParser.parseString(response.body?.string().orEmpty()).asJsonArray.firstOrNull()?.let { value ->
                val o = value.asJsonObject
                val id = o.get("id")?.asInt ?: return@let null
                val createdName = o.get("name")?.asString?.takeIf { it.isNotBlank() } ?: return@let null
                RoleplayCharacter(
                    id = id,
                    name = createdName,
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
    }.getOrNull()
}

@Composable
private fun CreateInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    multiline: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (multiline) Modifier.height(120.dp) else Modifier),
            placeholder = { Text(label) },
            shape = RoundedCornerShape(10.dp),
            minLines = if (multiline) 4 else 1,
            maxLines = if (multiline) 6 else 1,
        )
    }
}
