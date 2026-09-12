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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
    val context = LocalContext.current
    var serverUrl by remember { mutableStateOf("http://192.168.1.100:3000") }
    var passwordInput by remember { mutableStateOf("") }
    var archives by remember { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var currentArchive by remember { mutableStateOf<ArchiveItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }

    // 将用户输入的网页密码转换为 LANraragi 规范的 Base64 Bearer Token
    fun getAuthBearer(): String {
        if (passwordInput.isBlank()) return ""
        val trimmed = passwordInput.trim()
        val base64Key = Base64.encodeToString(trimmed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Bearer $base64Key"
    }

    // 自动同步阅读进度回 LANraragi
    fun syncProgress(archiveId: String, page: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanUrl = serverUrl.trimEnd('/')
                val reqBuilder = Request.Builder()
                    .url("$cleanUrl/api/archives/$archiveId/progress/$page")
                    .put("".toRequestBody("application/json".toMediaType()))
                val auth = getAuthBearer()
                if (auth.isNotBlank()) reqBuilder.header("Authorization", auth)
                client.newCall(reqBuilder.build()).execute().close()
            } catch (_: Exception) {}
        }
    }

    // 获取书架归档列表
    fun loadArchives() {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanUrl = serverUrl.trimEnd('/')
                val reqBuilder = Request.Builder().url("$cleanUrl/api/archives").get()
                val auth = getAuthBearer()
                if (auth.isNotBlank()) reqBuilder.header("Authorization", auth)
                
                val resp = client.newCall(reqBuilder.build()).execute()
                val respBody = resp.body?.string() ?: ""

                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "服务器返回错误: ${resp.code} $respBody"
                    }
                    return@launch
                }

                if (respBody.startsWith("{")) {
                    val jsonObj = JSONObject(respBody)
                    if (jsonObj.has("error")) {
                        withContext(Dispatchers.Main) {
                            errorMessage = "登录错误: ${jsonObj.getString("error")}\n请检查密码是否正确。"
                        }
                        return@launch
                    }
                }

                val jsonArray = JSONArray(respBody)
                val list = mutableListOf<ArchiveItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        ArchiveItem(
                            id = obj.getString("arcid"),
                            title = obj.optString("title", "未命名档案"),
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
            authBearer = getAuthBearer(),
            client = client,
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
                    Column(
                        modifier = Modifier.padding(16.dp).align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { showSettings = true }) {
                            Text("重新配置连接")
                        }
                    }
                } else if (archives.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("点击右上角设置配置服务器地址与密码")
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
                                    val auth = getAuthBearer()

                                    val imageRequest = ImageRequest.Builder(context)
                                        .data("$cleanUrl/api/archives/${arc.id}/thumbnail")
                                        .apply {
                                            if (auth.isNotBlank()) addHeader("Authorization", auth)
                                        }
                                        .crossfade(true)
                                        .build()

                                    AsyncImage(
                                        model = imageRequest,
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
                title = { Text("LANraragi 服务端配置") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("服务器地址 (包含端口)") },
                            placeholder = { Text("http://192.168.1.x:3000") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("网页登录密码 (或 API Key)") },
                            placeholder = { Text("输入 114514") },
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
    authBearer: String,
    client: OkHttpClient,
    onProgressChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(if (archive.progress > 0) archive.progress else 1) }
    var isExtracting by remember { mutableStateOf(true) }
    var extractError by remember { mutableStateOf<String?>(null) }
    val cleanUrl = serverUrl.trimEnd('/')

    // 进入阅读器时，先通知服务端解压归档文件缓存
    LaunchedEffect(archive.id) {
        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder()
                    .url("$cleanUrl/api/archives/${archive.id}/extract")
                    .post("".toRequestBody("application/json".toMediaType()))
                if (authBearer.isNotBlank()) reqBuilder.header("Authorization", authBearer)

                val resp = client.newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        isExtracting = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        extractError = "服务端预解压失败 (${resp.code})"
                        isExtracting = false
                    }
                }
                resp.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    extractError = "连接解压接口失败: ${e.localizedMessage}"
                    isExtracting = false
                }
            }
        }
    }

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
                        enabled = currentPage > 1 && !isExtracting,
                        onClick = {
                            currentPage--
                            onProgressChange(currentPage)
                        }
                    ) { Text("上一页") }

                    Text("$currentPage / ${archive.pageCount}")

                    Button(
                        enabled = currentPage < archive.pageCount && !isExtracting,
                        onClick = {
                            currentPage++
                            onProgressChange(currentPage)
                        }
                    ) { Text("下一页") }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (isExtracting) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在向服务器请求解压归档缓存...", color = Color.White)
                }
            } else if (extractError != null) {
                Text(text = extractError!!, color = MaterialTheme.colorScheme.error)
            } else {
                val imageRequest = ImageRequest.Builder(context)
                    .data("$cleanUrl/api/archives/${archive.id}/page?page=$currentPage")
                    .apply {
                        if (authBearer.isNotBlank()) addHeader("Authorization", authBearer)
                    }
                    .crossfade(true)
                    .build()

                AsyncImage(
                    model = imageRequest,
                    contentDescription = "第 $currentPage 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
