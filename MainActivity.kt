package com.ichaival.plus

import android.content.Context
import android.os.Bundle
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

// 全局 Cookie 管理器：完全模拟浏览器的 Session 会话行为
class CookieManager {
    private val cookieStore = mutableMapOf<String, MutableMap<String, Cookie>>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val hostCookies = cookieStore.getOrPut(url.host) { mutableMapOf() }
                    cookies.forEach { hostCookies[it.name] = it }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host]?.values?.toList() ?: emptyList()
                }
            })
            .build()
    }
}

val globalCookieManager = CookieManager()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val sharedPref = remember { context.getSharedPreferences("ichaival_prefs", Context.MODE_PRIVATE) }

    var serverUrl by remember { mutableStateOf(sharedPref.getString("url", "http://192.168.1.100:3000") ?: "") }
    var passwordInput by remember { mutableStateOf(sharedPref.getString("pwd", "") ?: "") }
    
    var archives by remember { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var currentArchive by remember { mutableStateOf<ArchiveItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val client = globalCookieManager.client

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .okHttpClient(client)
            .build()
    }

    fun syncProgress(archiveId: String, page: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanUrl = serverUrl.trimEnd('/')
                val req = Request.Builder()
                    .url("$cleanUrl/api/archives/$archiveId/progress/$page")
                    .put("".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Exception) {}
        }
    }

    fun loginAndLoad() {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanUrl = serverUrl.trimEnd('/')
                
                if (passwordInput.isNotBlank()) {
                    val formBody = FormBody.Builder()
                        .add("password", passwordInput.trim())
                        .build()
                    val loginReq = Request.Builder()
                        .url("$cleanUrl/login")
                        .post(formBody)
                        .build()
                    client.newCall(loginReq).execute().close()
                }

                val req = Request.Builder().url("$cleanUrl/api/archives").get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""

                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "连接失败，HTTP 错误码: ${resp.code}"
                    }
                    return@launch
                }

                if (body.startsWith("{")) {
                    val jsonObj = JSONObject(body)
                    if (jsonObj.has("error")) {
                        withContext(Dispatchers.Main) {
                            errorMessage = "密码错误或未登录: ${jsonObj.getString("error")}"
                        }
                        return@launch
                    }
                }

                val jsonArray = JSONArray(body)
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
                    sharedPref.edit().putString("url", serverUrl).putString("pwd", passwordInput).apply()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "网络连接异常: ${e.localizedMessage}"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (serverUrl.isNotBlank()) loginAndLoad()
    }

    if (currentArchive != null) {
        ReaderScreen(
            serverUrl = serverUrl,
            archive = currentArchive!!,
            client = client,
            imageLoader = imageLoader,
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
                        IconButton(onClick = { loginAndLoad() }) {
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
                        Button(onClick = { showSettings = true }) { Text("修改配置") }
                    }
                } else if (archives.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("尚未拉取书架内容，请点击右上角配置")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showSettings = true }) { Text("配置登录") }
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
                                    val thumbUrl = "$cleanUrl/api/archives/${arc.id}/thumbnail"

                                    AsyncImage(
                                        model = ImageRequest.Builder(context).data(thumbUrl).crossfade(true).build(),
                                        imageLoader = imageLoader,
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
                                        text = "阅读进度: ${arc.progress}/${arc.pageCount}P",
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
                title = { Text("LANraragi 网页版登录") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("服务器地址 (含端口)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("后台登录密码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showSettings = false
                        loginAndLoad()
                    }) { Text("保存并登录") }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) { Text("取消") }
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
    client: OkHttpClient,
    imageLoader: ImageLoader,
    onProgressChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(if (archive.progress > 0) (archive.progress - 1).coerceAtLeast(0) else 0) }
    var isExtracting by remember { mutableStateOf(true) }
    var extractError by remember { mutableStateOf<String?>(null) }
    val cleanUrl = serverUrl.trimEnd('/')

    // 标准解压流程：进入时发起 Extract 请求，超时放宽至 5 分钟
    LaunchedEffect(archive.id) {
        withContext(Dispatchers.IO) {
            try {
                // 专门给大文件解压分配一个超长超时的客户端（5分钟）
                val extractClient = client.newBuilder()
                    .readTimeout(5, TimeUnit.MINUTES)
                    .build()

                val req = Request.Builder()
                    .url("$cleanUrl/api/archives/${archive.id}/extract")
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = extractClient.newCall(req).execute()
                val body = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    val jsonObj = JSONObject(body)
                    val pagesJson = jsonObj.optJSONArray("pages")
                    if (pagesJson != null && pagesJson.length() > 0) {
                        val pageList = mutableListOf<String>()
                        for (i in 0 until pagesJson.length()) {
                            pageList.add(pagesJson.getString(i))
                        }
                        withContext(Dispatchers.Main) {
                            pages = pageList
                            isExtracting = false
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            extractError = "解析失败: 服务端未返回有效图片列表"
                            isExtracting = false
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        extractError = "服务端预解压失败 (HTTP ${resp.code})"
                        isExtracting = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    extractError = "解压超时或出错: ${e.localizedMessage}\n(NAS 处理大画册可能需要更长等待时间)"
                    isExtracting = false
                }
            }
        }
    }

    val totalCount = if (pages.isNotEmpty()) pages.size else archive.pageCount

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${archive.title} (${currentIndex + 1}/$totalCount)") },
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
                        enabled = currentIndex > 0 && !isExtracting,
                        onClick = {
                            currentIndex--
                            onProgressChange(currentIndex + 1)
                        }
                    ) { Text("上一页") }

                    Text("${currentIndex + 1} / $totalCount")

                    Button(
                        enabled = currentIndex < totalCount - 1 && !isExtracting,
                        onClick = {
                            currentIndex++
                            onProgressChange(currentIndex + 1)
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
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在通知 NAS 提取图片路径...\n如果压缩包超大，可能需要 1~3 分钟，请不要退出页面。",
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (extractError != null) {
                Text(text = extractError!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            } else if (pages.isNotEmpty()) {
                // 解压成功后，拿到真实的 path 加载，绝对不会再 404
                val currentPath = pages[currentIndex]
                val encodedPath = URLEncoder.encode(currentPath, "UTF-8")
                val imgUrl = "$cleanUrl/api/archives/${archive.id}/page?path=$encodedPath"

                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context).data(imgUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = "第 ${currentIndex + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    loading = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    error = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("网络错误，图片加载失败", color = Color.White)
                        }
                    }
                )
            }
        }
    }
}
