//package com.example.myapplication.ui.ai
//
//import android.annotation.SuppressLint
//import android.graphics.Bitmap
//import android.net.Uri
//import android.os.Build
//import android.util.Log
//import android.webkit.ValueCallback
//import android.webkit.WebChromeClient
//import android.webkit.WebResourceError
//import android.webkit.WebResourceRequest
//import android.webkit.WebResourceResponse
//import android.webkit.WebSettings
//import android.webkit.WebView
//import android.webkit.WebViewClient
//import androidx.compose.foundation.layout.*
//import androidx.compose.material.icons.Icons
//import androidx.compose.material.icons.filled.Clear
//import androidx.compose.material.icons.filled.Refresh
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.text.style.TextOverflow
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.viewinterop.AndroidView
//
//private const val TAG = "AIScreen"
//
///**
// * WebView 回调接口
// */
//interface WebViewCallback {
//    fun onPageStarted(url: String)
//    fun onPageFinished(url: String)
//    fun onProgressChanged(progress: Int)
//    fun onError(error: String)
//    fun onTitleChanged(title: String)
//}
//
///**
// * AI 页面 - 使用 WebView 打开豆包
// */
//@OptIn(ExperimentalMaterial3Api::class)
//@Composable
//fun AIScreen(
//    viewModel: AIViewModel,
//    modifier: Modifier = Modifier
//) {
//    val presetInputs by viewModel.presetInputs.collectAsState()
//    val aiOutputs by viewModel.aiOutputs.collectAsState()
//
//    // WebView 状态
//    var isLoading by remember { mutableStateOf(true) }
//    var loadProgress by remember { mutableStateOf(0) }
//    var pageTitle by remember { mutableStateOf("") }
//    var errorMessage by remember { mutableStateOf<String?>(null) }
//
//    // WebView 回调
//    val webViewCallback = remember {
//        object : WebViewCallback {
//            override fun onPageStarted(url: String) {
//                Log.d(TAG, "页面开始: $url")
//                isLoading = true
//                errorMessage = null
//            }
//
//            override fun onPageFinished(url: String) {
//                Log.d(TAG, "页面完成: $url")
//                isLoading = false
//                loadProgress = 100
//            }
//
//            override fun onProgressChanged(progress: Int) {
//                loadProgress = progress
//                if (progress == 100) {
//                    isLoading = false
//                }
//            }
//
//            override fun onError(error: String) {
//                errorMessage = error
//                isLoading = false
//            }
//
//            override fun onTitleChanged(title: String) {
//                pageTitle = title
//            }
//        }
//    }
//
//    // 加载商品数据
////    LaunchedEffect(Unit) {
////        viewModel.loadProductsWithoutWhole()
////    }
//
//    Scaffold(
//        topBar = {
//            TopAppBar(
//                title = { Text(pageTitle.ifEmpty { "AI 助手" }) },
//                colors = TopAppBarDefaults.topAppBarColors(
//                    containerColor = MaterialTheme.colorScheme.primaryContainer
//                ),
//                actions = {
//                    // 刷新按钮
//                    IconButton(onClick = {
//                        // 重新创建 WebView
//                    }) {
//                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
//                    }
//                    // 清空预选按钮
//                    if (presetInputs.isNotEmpty()) {
//                        IconButton(onClick = { viewModel.clearPresetInputs() }) {
//                            Icon(Icons.Default.Clear, contentDescription = "清空预选")
//                        }
//                    }
//                }
//            )
//        },
//        modifier = modifier
//    ) { paddingValues ->
//        Column(
//            modifier = Modifier
//                .fillMaxSize()
//                .padding(paddingValues)
//        ) {
//            // 顶部预选输入区域
//            if (presetInputs.isNotEmpty()) {
//                Card(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .padding(8.dp),
//                    colors = CardDefaults.cardColors(
//                        containerColor = MaterialTheme.colorScheme.surfaceVariant
//                    )
//                ) {
//                    Column(modifier = Modifier.padding(8.dp)) {
//                        Row(
//                            modifier = Modifier.fillMaxWidth(),
//                            horizontalArrangement = Arrangement.SpaceBetween,
//                            verticalAlignment = Alignment.CenterVertically
//                        ) {
//                            Text(
//                                text = "预选输入 (${presetInputs.size})",
//                                style = MaterialTheme.typography.bodySmall
//                            )
//                            TextButton(
//                                onClick = { viewModel.clearPresetInputs() },
//                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
//                            ) {
//                                Text("清空", style = MaterialTheme.typography.bodySmall)
//                            }
//                        }
//
//                        Row(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .heightIn(max = 40.dp),
//                            horizontalArrangement = Arrangement.spacedBy(4.dp)
//                        ) {
//                            presetInputs.forEachIndexed { index, input ->
//                                InputChip(
//                                    selected = false,
//                                    onClick = {
//                                        viewModel.selectPresetInput(index)
//                                    },
//                                    label = {
//                                        Text(
//                                            text = input,
//                                            maxLines = 1,
//                                            overflow = TextOverflow.Ellipsis
//                                        )
//                                    },
//                                    modifier = Modifier.height(28.dp)
//                                )
//                            }
//                        }
//                    }
//                }
//            }
//
//            // WebView 区域 - 占满剩余空间
//
//                // WebView 直接放在最底层
//                AndroidView(
//                    factory = { context ->
//                        createWebView(context, webViewCallback)
//                    },
//                    update = {
//
//                    },
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .weight(1f)
//                )
//
//                // 加载进度指示器 - 只在加载时显示
////                if (isLoading) {
////                    Column(
////                        modifier = Modifier.align(Alignment.Center),
////                        horizontalAlignment = Alignment.CenterHorizontally
////                    ) {
////                        CircularProgressIndicator()
////                        Spacer(modifier = Modifier.height(8.dp))
////                        Text(
////                            text = "加载中... ${loadProgress}%",
////                            style = MaterialTheme.typography.bodySmall
////                        )
////                    }
////                }
//
//
//            // 底部输出记录
////            if (aiOutputs.isNotEmpty()) {
////                Card(
////                    modifier = Modifier
////                        .fillMaxWidth()
////                        .heightIn(max = 100.dp)
////                        .padding(8.dp),
////                    colors = CardDefaults.cardColors(
////                        containerColor = MaterialTheme.colorScheme.secondaryContainer
////                    )
////                ) {
////                    Column(modifier = Modifier.padding(8.dp)) {
////                        Row(
////                            modifier = Modifier.fillMaxWidth(),
////                            horizontalArrangement = Arrangement.SpaceBetween
////                        ) {
////                            Text(
////                                text = "输出 (${aiOutputs.size})",
////                                style = MaterialTheme.typography.bodySmall
////                            )
////                            TextButton(
////                                onClick = { viewModel.clearAIOutputs() },
////                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
////                            ) {
////                                Text("清空", style = MaterialTheme.typography.bodySmall)
////                            }
////                        }
////                    }
////                }
//            }
//        }
//    }
//
//
//@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
//private fun createWebView(context: android.content.Context, callback: WebViewCallback): WebView {
//    Log.d(TAG, "创建 WebView")
//
//    return WebView(context).apply {
//        // 硬件加速
//        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
//
//        val webSettings: WebSettings = settings
//
//        // 基础设置
//        webSettings.javaScriptEnabled = true
//        webSettings.domStorageEnabled = true
//        webSettings.loadsImagesAutomatically = true
//
//        // 混合内容
//        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
//
//        // 文件访问
//        webSettings.allowFileAccess = true
//        webSettings.allowContentAccess = true
//
////        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
////            WebView.setWebContentsDebuggingEnabled(true);
////        }
//        // 数据库和窗口支持
//        webSettings.databaseEnabled = true
//        webSettings.setSupportMultipleWindows(true)
//        webSettings.javaScriptCanOpenWindowsAutomatically = true
//
//        webSettings.useWideViewPort = true  // 关键！允许视口缩放
//        webSettings.loadWithOverviewMode = true  // 关键！按比例缩放适应屏幕
//        webSettings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING  // 或 NORMAL
//
//        // ================== 修复2：缩放设置 ==================
//        webSettings.setSupportZoom(true)
//        webSettings.builtInZoomControls = true
//        webSettings.displayZoomControls = false
//
//        // 缓存
//        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
//
//        // 桌面端 User-Agent
//        webSettings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
//                "AppleWebKit/537.36 (KHTML, like Gecko) " +
//                "Chrome/120.0.0.0 Mobile Safari/537.36"
//
//        Log.d(TAG, "User-Agent: ${webSettings.userAgentString}")
//
//
//        // WebViewClient
//        webViewClient = object : WebViewClient() {
////            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
////                Log.d(TAG, "onPageStarted: $url")
////                callback.onPageStarted(url ?: "")
////                super.onPageStarted(view, url, favicon)
////            }
//
////            override fun onPageFinished(view: WebView?, url: String?) {
////                Log.d(TAG, "onPageFinished: $url")
////                callback.onPageFinished(url ?: "")
////
////                // 检查内容
////                view?.postDelayed({
////                    view.contentHeight.let { height ->
////                        Log.d(TAG, "内容高度: $height")
////                    }
////                }, 500)
////                super.onPageFinished(view, url)
////            }
//
//            override fun shouldOverrideUrlLoading(
//                view: WebView?,
//                request: WebResourceRequest?
//            ): Boolean {
//                Log.d(TAG, "shouldOverrideUrlLoading: ${request?.url}")
//                return false
//            }
//
//            override fun onLoadResource(view: WebView?, url: String?) {
//                Log.d(TAG, "加载资源: $url")
//                super.onLoadResource(view, url)
//            }
//
//            override fun shouldInterceptRequest(
//                view: WebView?,
//                request: WebResourceRequest?
//            ): WebResourceResponse? {
//                Log.d(TAG, "请求: ${request?.url}, 头部: ${request?.requestHeaders}")
//                return super.shouldInterceptRequest(view, request)
//            }
//
////            override fun onReceivedError(
////                view: WebView?,
////                request: WebResourceRequest?,
////                error: WebResourceError?
////            ) {
////                super.onReceivedError(view, request, error)
////                val url = request?.url.toString()
////                val desc = error?.description ?: "Unknown"
////                val code = error?.errorCode ?: -1
////                Log.e(TAG, "onReceivedError: $url, code=$code, desc=$desc")
////                callback.onError("$code: $desc")
////            }
//        }
//
//        // ChromeClient
//        webChromeClient = object : WebChromeClient() {
//            override fun onProgressChanged(view: WebView?, newProgress: Int) {
//                super.onProgressChanged(view, newProgress)
//                Log.d(TAG, "onProgressChanged: $newProgress%")
//                callback.onProgressChanged(newProgress)
//            }
//
//            override fun onReceivedTitle(view: WebView?, title: String?) {
//                super.onReceivedTitle(view, title)
//                Log.d(TAG, "onReceivedTitle: $title")
//                callback.onTitleChanged(title ?: "")
//            }
//            override fun onShowFileChooser(
//                webView: WebView,
//                filePathCallback: ValueCallback<Array<Uri>>,
//                fileChooserParams: WebChromeClient.FileChooserParams
//            ): Boolean {
//                // 处理文件上传
//                return true
//            }
//
//            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
//                msg?.let {
//                    Log.d(TAG, "JS Console: ${it.message()}")
//                }
//                return true
//            }
//        }
//
//        // JS Interface
//        addJavascriptInterface(JSInterface(), "AndroidInterface")
//
//
//        loadUrl("https://chat.deepseek.com/")
//    }
//}
//
///**
// * JS 接口
// */
//class JSInterface {
//    @android.webkit.JavascriptInterface
//    fun log(message: String) {
//        Log.d("AI-JS", message)
//    }
//}
package com.example.myapplication.ui.ai

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private const val TAG = "AIScreen"

/**
 * WebView 回调接口
 */
interface WebViewCallback {
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onProgressChanged(progress: Int)
    fun onError(error: String)
    fun onTitleChanged(title: String)
}

/**
 * AI 页面 - 使用 WebView 打开豆包
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AIScreen(
    viewModel: AIViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val presetInputs by viewModel.presetInputs.collectAsState()

    // 输入框状态
    var userInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // 展开/折叠状态
    var isContentExpanded by remember { mutableStateOf(false) }

    // WebView 状态
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf(0) }
    var pageTitle by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 成功提示状态
    var showSuccessMessage by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }

    // 显示成功消息
    LaunchedEffect(showSuccessMessage) {
        if (showSuccessMessage) {
            delay(2000) // 显示2秒
            showSuccessMessage = false
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle.ifEmpty { "AI 助手" }) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    // 刷新按钮
                    IconButton(onClick = {
                        // 这里可以重新加载WebView
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ============ 卡片1：提示词和预设输入 ============
            // 在 Column 内部，替换原来的 Card 代码：

// ============ 简化版：提示词和预设输入卡片 ============
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 标题行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "预设输入列表 (${presetInputs.size})",
                            style = MaterialTheme.typography.titleSmall
                        )

                        // 复制按钮
                        IconButton(
                            onClick = {
                                // 构建要复制的文本内容
                                val content = if (presetInputs.isNotEmpty()) {
                                    buildString {
                                        appendLine("以下为所有的商品名称，单子中只可能出现以下货物：")
                                        // 只复制商品列表，没有额外的提示词
                                        presetInputs.forEach { item ->
                                            appendLine(item)
                                        }
                                        appendLine("我可能会提供订单的图片，也可能在下面附上文字版的订单。我需要你顺序回复所有的货物名称。")
                                        appendLine("记住，只回复货物名称，不回复任何其他的字、序号或数量等信息，且每个货物需要分行回复")
                                        appendLine("以下是我的订单的信息：")
                                    }
                                } else {
                                    "暂无预设内容"
                                }

                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("预设内容", content)
                                clipboard.setPrimaryClip(clip)

                                // 显示成功提示
                                successMessage = "已复制到剪贴板"
                                showSuccessMessage = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "复制内容",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 内容展示区域
//                    if (presetInputs.isEmpty()) {
//                        Text(
//                            text = "暂无预设内容，请在下方添加",
//                            style = MaterialTheme.typography.bodySmall,
//                            color = MaterialTheme.colorScheme.onSurfaceVariant
//                        )
//                    } else {
//                        // 使用 LazyColumn 进行高效滚动显示
//                        LazyColumn(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .heightIn(max = 150.dp), // 限制最大高度，可滚动
//                            verticalArrangement = Arrangement.spacedBy(4.dp)
//                        ) {
//                            items(presetInputs.size) { index ->
//                                Text(
//                                    text = "${presetInputs[index]}",
//                                    style = MaterialTheme.typography.bodyMedium,
//                                    modifier = Modifier.fillMaxWidth()
//                                )
//                            }
//                        }
//                    }
                }
            }
            // ============ 卡片2：输入框和记录按钮 ============
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "添加预设输入",
                        style = MaterialTheme.typography.titleSmall
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 输入框
                        OutlinedTextField(
                            value = userInput,
                            onValueChange = { userInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            placeholder = { Text("输入商品名称...") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (userInput.isNotBlank()) {
                                        viewModel.addoutput(userInput.trim())
                                        userInput = ""
                                        focusRequester.requestFocus()
                                    }
                                }
                            )
                        )

                        // 记录按钮
                        Button(
                            onClick = {
                                if (userInput.isNotBlank()) {
                                    viewModel.addoutput(userInput.trim())
                                    userInput = ""
                                    focusRequester.requestFocus()
                                }
                            },
                            enabled = userInput.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "记录",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("记录")
                        }
                    }

                    // 提示文字
                    Text(
                        text = "提示：输入商品名称后点击记录按钮，会分行添加到预设列表中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 成功消息提示
            if (showSuccessMessage) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = successMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // WebView 区域
//            Box(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .weight(1f)
//            ) {
//                AndroidView(
//                    factory = { context ->
//                        createWebView(context, webViewCallback)
//                    },
//                    update = {},
//                    modifier = Modifier.fillMaxSize()
//                )
//
//                // 加载指示器
//                if (isLoading) {
//                    Surface(
//                        modifier = Modifier.fillMaxSize(),
//                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
//                    ) {
//                        Column(
//                            modifier = Modifier.fillMaxSize(),
//                            horizontalAlignment = Alignment.CenterHorizontally,
//                            verticalArrangement = Arrangement.Center
//                        ) {
//                            CircularProgressIndicator()
//                            Spacer(modifier = Modifier.height(12.dp))
//                            Text(
//                                text = "加载中... $loadProgress%",
//                                style = MaterialTheme.typography.bodySmall
//                            )
//                        }
//                    }
//                }
//            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun createWebView(context: android.content.Context, callback: WebViewCallback): WebView {
    Log.d(TAG, "创建 WebView")

    return WebView(context).apply {
        // 硬件加速
        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        val webSettings: WebSettings = settings

        // 基础设置
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadsImagesAutomatically = true

        // 混合内容
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 文件访问
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        // 数据库和窗口支持
        webSettings.databaseEnabled = true
        webSettings.setSupportMultipleWindows(true)
        webSettings.javaScriptCanOpenWindowsAutomatically = true

        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        // 缩放设置
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        // 缓存
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT

        // User-Agent
        webSettings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        // WebViewClient
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                callback.onPageStarted(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                callback.onPageFinished(url ?: "")
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                Log.d(TAG, "shouldOverrideUrlLoading: ${request?.url}")
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val url = request?.url.toString()
                val desc = error?.description ?: "Unknown"
                val code = error?.errorCode ?: -1
                Log.e(TAG, "onReceivedError: $url, code=$code, desc=$desc")
                callback.onError("$code: $desc")
            }
        }

        // ChromeClient
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                callback.onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                callback.onTitleChanged(title ?: "")
            }
        }

        loadUrl("https://chat.deepseek.com/")
    }
}
