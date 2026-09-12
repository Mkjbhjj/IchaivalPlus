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

// 全局带 CookieJar 的 OkHttpClient，行为与浏览器网络栈完全一致
class CookieManager {
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies.toMutableList()
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
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

    // 配置专属于此 OkHttpClient 的图片加载器
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .okHttpClient(client)
            .build()
    }

    // 网页端进度上报接口
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

    // 像网页一样先走表单登录获取 Session Cookie，随后拉取画廊列表
    fun loginAndLoad() {
        scope.launch(Dispatchers.IO) {
            try {
                val cleanUrl = serverUrl.trimEnd('/')
                
                // 1. 如果填了密码，先向 /login 发起表单登录
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

                // 2. 带上 Cookie 请求归档列表
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
                            errorMessage = "认证失败: ${jsonObj.getString("error")}"
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
                    // 保存配置
                    sharedPref.edit().putString("url", serverUrl).putString("pwd", passwordInput).apply()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "网络连接异常: ${e.localizedMessage}"
                }
            }
        }
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
                        Button(onClick = { showSettings = true }) { Text("修改服务器地址与密码") }
                    }
                } else if (archives.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("尚未获取到书架内容")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { showSettings = true }) { Text("配置连接") }
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
                title = { Text("LANraragi 网页登录设置") },
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
                            label = { Text("网页端密码") },
                            placeholder = { Text("例如 114514") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showSettings = false
                        loginAndLoad()
                    }) { Text("像网页端一样登录") }
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
    var isLoadingFiles by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val cleanUrl = serverUrl.trimEnd('/')

    // 复刻网页端 reader.js：进入阅读器先通过 /api/archives/{id}/files 获取实际图片路径数组
    LaunchedEffect(archive.id) {
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$cleanUrl/api/archives/${archive.id}/files").get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    val jsonObj = JSONObject(body)
                    val pagesJson = jsonObj.getJSONArray("pages")
                    val pageList = mutableListOf<String>()
                    for (i in 0 until pagesJson.length()) {
                        pageList.add(pagesJson.getString(i))
                    }
                    withContext(Dispatchers.Main) {
                        pages = pageList
                        isLoadingFiles = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        loadError = "获取页面清单失败 (${resp.code})"
                        isLoadingFiles = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadError = "加载异常: ${e.localizedMessage}"
                    isLoadingFiles = false
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
                        enabled = currentIndex > 0 && !isLoadingFiles,
                        onClick = {
                            currentIndex--
                            onProgressChange(currentIndex + 1)
                        }
                    ) { Text("上一页") }

                    Text("${currentIndex + 1} / $totalCount")

                    Button(
                        enabled = currentIndex < totalCount - 1 && !isLoadingFiles,
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
            if (isLoadingFiles) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在载入漫画页面清单...", color = Color.White)
                }
            } else if (loadError != null) {
                Text(text = loadError!!, color = MaterialTheme.colorScheme.error)
            } else if (pages.isNotEmpty()) {
                // 使用解压后的真实相对路径拼接图片 URL（网页端同款机制）
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
                            Text("图片加载失败，请检查网络", color = Color.White)
                        }
                    }
                )
            }
        }
    }
}
