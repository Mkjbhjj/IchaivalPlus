package com.ichaival.plus

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}

data class ArchiveItem(val id: String, val title: String, val pageCount: Int, var progress: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var serverUrl by remember { mutableStateOf("http://192.168.1.100:3000") }
    var apiKey by remember { mutableStateOf("") }
    var archives by remember { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var currentArchive by remember { mutableStateOf<ArchiveItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    fun buildAuthHeader(): String {
        return if (apiKey.isNotBlank()) {
            val encoded = Base64.encodeToString(apiKey.trim().toByteArray(), Base64.NO_WRAP)
            "Bearer $encoded"
        } else ""
    }

    fun syncProgress(archiveId: String, page: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanUrl = serverUrl.trimEnd('/')
                val reqBuilder = Request.Builder()
                    .url("$cleanUrl/api/archives/$archiveId/progress/$page")
                    .put("".toRequestBody("application/json".toMediaType()))
                val auth = buildAuthHeader()
                if (auth.isNotBlank()) reqBuilder.header("Authorization", auth)
                client.newCall(reqBuilder.build()).execute().close()
            } catch (_: Exception) {}
        }
    }

    fun loadArchives() {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanUrl = serverUrl.trimEnd('/')
                val reqBuilder = Request.Builder().url("$cleanUrl/api/archives").get()
                val auth = buildAuthHeader()
                if (auth.isNotBlank()) reqBuilder.header("Authorization", auth)
                val resp = client.newCall(reqBuilder.build()).execute()
                val jsonStr = resp.body?.string() ?: "[]"
                val jsonArray = JSONArray(jsonStr)
                val list = mutableListOf<ArchiveItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        ArchiveItem(
                            id = obj.getString("arcid"),
                            title = obj.optString("title", "Unknown"),
                            pageCount = obj.optInt("pagecount", 1),
                            progress = obj.optInt("progress", 0)
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    archives = list
                    errorMessage = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "连接失败: ${e.localizedMessage}"
                }
            }
        }
    }

    if (currentArchive != null) {
        ReaderScreen(
            serverUrl = serverUrl,
            archive = currentArchive!!,
            authHeader = buildAuthHeader(),
            onProgressChange = { p ->
                currentArchive?.progress = p
                syncProgress(currentArchive!!.id, p)
            },
            onBack = { currentArchive = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ichaival+ (LANraragi)") },
                    actions = {
                        IconButton(onClick = { loadArchives() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).align(Alignment.Center)
                    )
                } else if (archives.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("点击右上角设置配置服务器地址与 API Key")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showSettings = true }) {
                            Text("配置连接")
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(archives) { arc ->
                            Card(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .fillMaxWidth()
                                    .clickable { currentArchive = arc },
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Column {
                                    val cleanUrl = serverUrl.trimEnd('/')
                                    AsyncImage(
                                        model = "$cleanUrl/api/archives/${arc.id}/thumbnail",
                                        contentDescription = arc.title,
                                        modifier = Modifier.height(200.dp).fillMaxWidth(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Text(
                                        text = arc.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                    Text(
                                        text = "进度: ${arc.progress}/${arc.pageCount}P",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("LANraragi 服务器配置") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("服务器地址 (包含端口)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key (或密码)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showSettings = false
                        loadArchives()
                    }) {
                        Text("保存并连接")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    serverUrl: String,
    archive: ArchiveItem,
    authHeader: String,
    onProgressChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    var currentPage by remember { mutableStateOf(if (archive.progress > 0) archive.progress else 1) }
    val cleanUrl = serverUrl.trimEnd('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${archive.title} ($currentPage/${archive.pageCount})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        enabled = currentPage > 1,
                        onClick = {
                            currentPage--
                            onProgressChange(currentPage)
                        }
                    ) { Text("上一页") }

                    Text("$currentPage / ${archive.pageCount}")

                    Button(
                        enabled = currentPage < archive.pageCount,
                        onClick = {
                            currentPage++
                            onProgressChange(currentPage)
                        }
                    ) { Text("下一页") }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = "$cleanUrl/api/archives/${archive.id}/page?page=$currentPage",
                contentDescription = "第 $currentPage 页",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}
