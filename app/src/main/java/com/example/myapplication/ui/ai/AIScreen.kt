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
