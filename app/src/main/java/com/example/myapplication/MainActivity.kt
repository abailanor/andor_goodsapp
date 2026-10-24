package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.app.App
import com.example.myapplication.app.ServiceLocator
import com.example.myapplication.data.PendingOrderLoader
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.SessionRepository.SessionState
import com.example.myapplication.ui.ModeSwitchBar
import com.example.myapplication.ui.ai.AIScreen
import com.example.myapplication.ui.ai.AIViewModel
import com.example.myapplication.ui.history.OrderHistoryScreen
import com.example.myapplication.ui.login.LoginScreen
import com.example.myapplication.ui.login.LoginViewModel
import com.example.myapplication.ui.more.MoreScreen
import com.example.myapplication.ui.order.OrderScreen
import com.example.myapplication.ui.rememberAppBackgroundColor
import com.example.myapplication.ui.order.OrderViewModel
import com.example.myapplication.ui.orders.OrdersScreen
import com.example.myapplication.ui.orders.OrdersViewModel
import com.example.myapplication.ui.pending.PendingOrdersScreen
import com.example.myapplication.ui.pending.PendingOrdersViewModel
import com.example.myapplication.ui.pending.PendingOrdersViewModelFactory
import com.example.myapplication.ui.product.ProductManagementScreen
import com.example.myapplication.ui.product.ProductViewModel
import com.example.myapplication.ui.product.ProductViewModelFactory
import com.example.myapplication.ui.settings.SettingsScreen
import com.example.myapplication.ui.settings.SettingsViewModel
import com.example.myapplication.ui.stats.StatsScreen
import com.example.myapplication.ui.stats.StatsViewModel
import com.example.myapplication.ui.sync.SyncScreen
import com.example.myapplication.ui.sync.SyncViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

sealed class Screen(val route: String) {
    // ---- 底部导航的五个 ----
    object Order : Screen("order")
    object Orders : Screen("orders")
    object ProductManagement : Screen("product_management")
    object Stats : Screen("stats")
    object More : Screen("more")

    // ---- 非导航项 ----
    /** 登录**不是**一道闸门，随时可以从「我的」进去，也可以在启动页选。 */
    object Login : Screen("login")

    /**
     * 待开单：记下来还没成交的单。
     *
     * ⚠ 它**继续用** `filesDir/PendingOrders/` 下的 JSON 文件，不进 Room、不参与同步 ——
     * 这是明确的产品决定：待开单是本机的草稿，换设备不需要看到它。
     *
     * ⚠ 注释里不要写"斜杠 + 星号 + .json"那种通配路径：Kotlin 的块注释是**可嵌套**的，
     * 那两个字符连在一起会被当成嵌套注释的开头，整个文件从此报"未闭合的注释"。
     */
    object PendingOrders : Screen("pending_orders")

    /**
     * 旧历史订单页（读 `filesDir/Orders/` 下的 JSON 文件）。
     *
     * ⚠ **临时保留**。新架构的历史订单在 [Orders]（读 `order_index`），但用户过去几年
     * 的订单只存在于那些 JSON 文件里，P8 的数据迁移向导做完之前删掉这一页
     * 就等于让那些订单在界面上再也打不开。P8 之后这一项要摘掉。
     */
    object LegacyHistory : Screen("history")

    object AI : Screen("ai")

    /** 设置页。从「我的」进。 */
    object Settings : Screen("settings")

    /** 同步状态页（诊断用）。从设置页进。 */
    object Sync : Screen("sync")
}

/** 底部导航的五个位置，顺序即显示顺序。 */
private val BOTTOM_TABS = listOf(
    Screen.Order to "开单",
    // 待开单回到导航栏：它是开单流程的一部分（挂起 → 之后取回），
    // 藏在「我的」里面等于每次取单都要多点两下，而收银台是按秒算的。
    Screen.PendingOrders to "待开单",
    Screen.Orders to "订单",
    Screen.ProductManagement to "商品",
    Screen.Stats to "统计",
    Screen.More to "我的",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 走 ServiceLocator 而不是自己 `AppDatabase.getDatabase(this)` + `ProductRepository(...)`：
        // 数据库与仓库必须是**全进程一份**，两条各自 new 的路径迟早会分叉
        // （尤其是倒排索引与商品写入要在同一个事务里维护）。
        val locator = App.locatorOf(this)
        val repository = locator.products
        // 进程内只跑一次（转屏会重建 Activity，重跑就会再发一次自动登录 → 撞 5/min 限速）
        locator.startSessionOnce()

        setContent {
            MyApplicationTheme {
                MainScreen(locator = locator, repository = repository)
            }
        }
    }
}

/**
 * 导航栏底色 = 登录状态。**白 = 已登录，灰 = 其它一切**（离线 / 登录中 / 登录失败）。
 *
 * 用底色而不是弹提示，是因为登录**不许阻塞用户**：点完登录就回主界面继续开单，
 * 登录在后台跑。那用户怎么知道成没成？只能靠一个一直在那儿、扫一眼就能看到的信号。
 */
private val NAV_ONLINE = Color.White
private val NAV_OFFLINE = Color(0xFFBDBDBD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(locator: ServiceLocator, repository: ProductRepository) {
    val navController = rememberNavController()

    // 创建共享的 AIViewModel
    val sharedAIViewModel = remember { AIViewModel(repository) }

    val sessionState by locator.sessions.state.collectAsState()
    val online = sessionState is SessionState.Online
    val currentRoute by navController.currentBackStackEntryAsState()

    // 登录页不显示底部导航：它是全屏的一页，而且用户可能正处在"还没选过"的状态，
    // 底下摆五个 tab 会让他以为必须登录才能用。
    val onLoginScreen = currentRoute?.destination?.route == Screen.Login.route

    Scaffold(
        bottomBar = {
            if (!onLoginScreen) {
                NavigationBar(containerColor = if (online) NAV_ONLINE else NAV_OFFLINE) {
                    for ((screen, label) in BOTTOM_TABS) {
                        NavigationBarItem(
                            icon = { Text(label) },
                            selected = currentRoute?.destination?.route == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    // 不销毁页面，只是切换到它
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
            }
        },
        // ⚠ 底色跟着开单模式变：进货模式下整个应用变成浅橙色。
        // 这是防误操作的**唯一实时**信号 —— 店员照着昨天的界面点下去，
        // 开出来的是一张不计营收、不扣库存的 A 类进货单，那一单会凭空消失。
        containerColor = rememberAppBackgroundColor(),
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Order.route,
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            composable(Screen.Order.route) {
                val context = LocalContext.current
                val viewModel = remember {
                    OrderViewModel(
                        repository = repository,
                        context = context,
                        outbox = locator.outboxStore,
                        uploader = locator.outboxUploader,
                        storeKey = { locator.currentStoreKey() },
                        // ⚠ ownerName 拼 fileId，loginName 当 writer。两者在员工登录时**不同**，
                        // 混用的后果是订单落进一个只有本机知道的私有文件（见 fileOwnerOrNull）。
                        ownerName = { locator.currentOwnerName() },
                        loginName = { locator.session.value?.username ?: "" },
                    )
                }

                // 检查是否有待加载的待开单数据
                LaunchedEffect(Unit) {
                    if (PendingOrderLoader.hasData()) {
                        val (items, fileName, targetOrder) = PendingOrderLoader.consume()
                        if (items != null && fileName != null) {
                            // 先清除当前订单，再加载新订单
                            viewModel.clearOrder()
                            viewModel.loadPendingOrderItems(items, fileName, targetOrder)
                        }
                    }
                }

                // 模式切换条吸顶：底色会在列表滚动后被盖住，但这一条永远看得见。
                Column(Modifier.fillMaxSize()) {
                    // ⚠ 必须把切换**转发给 ViewModel**。切换条自己只改全局的
                    // CurrentOrderMode，而购物车是 ViewModel 里的两辆车 ——
                    // 不转发的话底色变了、车没换，用户看到的还是销售单的内容。
                    ModeSwitchBar(onModeChanged = { viewModel.setMode(it) })
                    Box(Modifier.weight(1f)) {
                        OrderScreen(viewModel = viewModel, aiViewModel = sharedAIViewModel)
                    }
                }
            }

            composable(Screen.Orders.route) {
                val viewModel = remember { OrdersViewModel(locator.orders, locator.priceDrift) }
                OrdersScreen(viewModel = viewModel)
            }

            composable(Screen.ProductManagement.route) {
                val viewModel = viewModel<ProductViewModel>(factory = ProductViewModelFactory(repository))
                ProductManagementScreen(viewModel = viewModel)
            }

            composable(Screen.Stats.route) {
                val viewModel = remember { StatsViewModel(locator.stats) }
                StatsScreen(viewModel = viewModel)
            }

            composable(Screen.More.route) {
                MoreScreen(
                    state = locator.sessions.state,
                    hotspot = locator.hotspotSync,
                    onLogin = { navController.navigate(Screen.Login.route) { launchSingleTop = true } },
                    onSettings = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
                    onPendingOrders = {
                        navController.navigate(Screen.PendingOrders.route) { launchSingleTop = true }
                    },
                    onLegacyHistory = {
                        navController.navigate(Screen.LegacyHistory.route) { launchSingleTop = true }
                    },
                    onAi = { navController.navigate(Screen.AI.route) { launchSingleTop = true } },
                    onLogout = { locator.sessions.logout() },
                )
            }

            composable(Screen.Login.route) {
                val loginViewModel = remember { LoginViewModel(locator.sessions) }
                LoginScreen(
                    viewModel = loginViewModel,
                    // popBackStack 而不是 navigate：登录页是从「我的」推上来的，
                    // 回去就该回到「我的」，而不是又落到开单页。
                    onDismiss = { navController.popBackStack() },
                )
            }

            composable(Screen.Settings.route) {
                val vm = remember { SettingsViewModel(locator.settings) }
                SettingsScreen(
                    viewModel = vm,
                    shopName = (sessionState as? SessionState.Online)?.shopName,
                    onSync = { navController.navigate(Screen.Sync.route) { launchSingleTop = true } },
                )
            }

            composable(Screen.Sync.route) {
                val vm = remember {
                    SyncViewModel(
                        scheduler = locator.syncScheduler,
                        verifier = locator.mirrorVerifier,
                        changeLog = locator.changeLog,
                        dao = locator.orderFileDao,
                        storeKey = { locator.currentStoreKey() },
                        // ⚠ appScope 而不是 viewModelScope：「校验镜像」要跑很久，
                        // 划走页面就取消会留下半个 mirror-tmp 临时文件，
                        // 而下一次校验可能拿它当服务器副本去比对 → 报一个不存在的分歧。
                        scope = locator.appScope,
                        audit = { locator.changeLog.log("SYNC-UI", it) },
                    )
                }
                SyncScreen(viewModel = vm)
            }

            composable(Screen.PendingOrders.route) {
                val context = LocalContext.current
                val viewModel = viewModel<PendingOrdersViewModel>(
                    factory = PendingOrdersViewModelFactory(repository, context)
                )
                Column(Modifier.fillMaxSize()) {
                    ModeSwitchBar()
                    Box(Modifier.weight(1f)) {
                        PendingOrdersScreen(
                            viewModel = viewModel,
                            onNavigateToOrder = {
                                navController.navigate(Screen.Order.route) { launchSingleTop = true }
                            }
                        )
                    }
                }
            }

            composable(Screen.LegacyHistory.route) {
                Column(Modifier.fillMaxSize()) {
                    ModeSwitchBar()
                    Box(Modifier.weight(1f)) { OrderHistoryScreen() }
                }
            }

            composable(Screen.AI.route) {
                AIScreen(viewModel = sharedAIViewModel)
            }
        }
    }
}
