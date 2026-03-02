package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.PendingOrderLoader
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.ui.history.OrderHistoryScreen
import com.example.myapplication.ui.ai.AIScreen
import com.example.myapplication.ui.ai.AIViewModel
import com.example.myapplication.ui.ai.AIViewModelFactory
import com.example.myapplication.ui.order.OrderScreen
import com.example.myapplication.ui.order.OrderViewModel
import com.example.myapplication.ui.pending.PendingOrdersScreen
import com.example.myapplication.ui.pending.PendingOrdersViewModel
import com.example.myapplication.ui.pending.PendingOrdersViewModelFactory
import com.example.myapplication.ui.product.ProductManagementScreen
import com.example.myapplication.ui.product.ProductViewModel
import com.example.myapplication.ui.product.ProductViewModelFactory
import com.example.myapplication.ui.theme.MyApplicationTheme

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object ProductManagement : Screen("product_management")
    object Order : Screen("order")
    object History : Screen("history")
    object PendingOrders : Screen("pending_orders")
    object AI : Screen("ai")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(this)
        val repository = ProductRepository(database.productDao())

        setContent {
            MyApplicationTheme {
                MainScreen(repository = repository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: ProductRepository) {
    val navController = rememberNavController()
    
    // 创建共享的 AIViewModel
    val sharedAIViewModel = remember { AIViewModel(repository) }
    
    // 加载 AI 预选输入
//    LaunchedEffect(Unit) {
//        sharedAIViewModel.loadProductsWithoutWhole()
//    }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Text("开单") },
                    selected = navController.currentDestination?.route == Screen.Order.route,
                    onClick = {
                        navController.navigate(Screen.Order.route) {
                            // 不销毁开单页面，只是切换到它
                            launchSingleTop = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Text("待开单") },
                    selected = navController.currentDestination?.route == Screen.PendingOrders.route,
                    onClick = {
                        navController.navigate(Screen.PendingOrders.route) {
                            launchSingleTop = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Text("商品管理") },
                    selected = navController.currentDestination?.route == Screen.ProductManagement.route,
                    onClick = {
                        navController.navigate(Screen.ProductManagement.route) {
                            launchSingleTop = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Text("历史订单") },
                    selected = navController.currentDestination?.route == Screen.History.route,
                    onClick = {
                        navController.navigate(Screen.History.route) {
                            launchSingleTop = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Text("AI") },
                    selected = navController.currentDestination?.route == Screen.AI.route,
                    onClick = {
                        navController.navigate(Screen.AI.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        },
        containerColor = Color(0x0FF5F5F5),
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Order.route,
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            composable(Screen.Order.route) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val viewModel = remember {
                    OrderViewModel(repository, context)
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

                OrderScreen(viewModel = viewModel, aiViewModel = sharedAIViewModel)
            }

            composable(Screen.PendingOrders.route) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val viewModel = viewModel<PendingOrdersViewModel>(
                    factory = PendingOrdersViewModelFactory(repository, context)
                )

                PendingOrdersScreen(
                    viewModel = viewModel,
                    onNavigateToOrder = {
                        navController.navigate(Screen.Order.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Screen.ProductManagement.route) {
                val viewModel = viewModel<ProductViewModel>(
                    factory = ProductViewModelFactory(repository)
                )

                ProductManagementScreen(viewModel = viewModel)
            }

            composable(Screen.History.route) {
                OrderHistoryScreen()
            }

            composable(Screen.AI.route) {
                AIScreen(viewModel = sharedAIViewModel)
            }
        }
    }
}
