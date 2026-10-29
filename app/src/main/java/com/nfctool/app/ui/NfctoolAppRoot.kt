package com.nfctool.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfctool.app.core.nfc.ndef.SharedContentParser
import com.nfctool.app.ui.components.RadarPulse
import com.nfctool.app.ui.pages.ClonePage
import com.nfctool.app.ui.pages.ConsolePage
import com.nfctool.app.ui.pages.HistoryPage
import com.nfctool.app.ui.pages.ReadPage
import com.nfctool.app.ui.pages.SettingsPage
import com.nfctool.app.ui.pages.WriteDetailPage
import com.nfctool.app.ui.pages.WritePage
import androidx.annotation.StringRes
import com.nfctool.app.ui.theme.extras
import androidx.compose.ui.res.stringResource
import com.nfctool.app.R

/**
 * 底部导航目的地。
 */
sealed class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    data object Read : Destination("read", R.string.tab_read, Icons.Rounded.Sensors)
    data object Write : Destination("write", R.string.tab_write, Icons.Rounded.Edit)
    data object Console : Destination("console", R.string.tab_console, Icons.Rounded.Terminal)
    data object History : Destination("history", R.string.tab_history, Icons.Rounded.History)
    data object Settings : Destination("settings", R.string.tab_settings, Icons.Rounded.Settings)
}

/** 写入详情页路由前缀，实际路由为 `write_detail/{entryId}` */
private const val WRITE_DETAIL_ROUTE = "write_detail/{entryId}"

/**
 * 复制标签页路由。
 *
 * 它不是底部导航的第六个 tab —— 复制是「有始有终」的任务型页面
 * （读源卡 → 写目标卡），混在常驻 tab 里会破坏底部栏的对称性。
 * 入口放在写入页顶部与读取页读完卡片之后。
 */
private const val CLONE_ROUTE = "clone"

private const val ARG_ENTRY_ID = "entryId"

private val bottomDestinations = listOf(
    Destination.Read,
    Destination.Write,
    Destination.Console,
    Destination.History,
    Destination.Settings,
)

@Composable
fun NfctoolAppRoot(vm: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val prefill by vm.pendingPrefill.collectAsStateWithLifecycle()
    val prefillSeq by vm.prefillSeq.collectAsStateWithLifecycle()

    // 收到分享内容 → 跳到对应的写入详情页，表单会自动预填
    // 用序号做 key：连续分享相同内容时也能重新触发
    LaunchedEffect(prefillSeq) {
        val target = prefill ?: return@LaunchedEffect
        // 内容非法（如坐标越界）时不跳转，ViewModel 已给出错误提示
        if (!target.isValid) return@LaunchedEffect
        val entryId = when (target.kind) {
            SharedContentParser.Kind.GEO -> "geo"
            SharedContentParser.Kind.URI -> "uri"
            SharedContentParser.Kind.TEXT -> "text"
            SharedContentParser.Kind.VCARD -> "vcard"
            SharedContentParser.Kind.IMAGE -> "image"
        }
        navController.navigate("write_detail/$entryId") {
            launchSingleTop = true
        }
    }

    // 系统返回手势 / 返回键统一在这里接管。
    //
    // 为什么必须自己接：manifest 里已关掉预测式返回动画，此时系统不再替我们
    // 播任何过渡。若不注册回调，在写入详情页按返回会直接 finish 掉整个 Activity
    // （等于退出应用），而不是回到写入目录页。
    //
    // 只在"还有上一级"时启用：根页面（读取页）不拦截，让系统正常退出应用。
    //
    // ⚠️ 这里**不能**用 `navController.previousBackStackEntry` ——
    // 那是在组合期直接读 NavController 的内部返回栈，而此刻 NavHost 可能还没
    // 把 graph 挂上去，时机不对会抛 IllegalStateException。
    // 改成订阅 currentBackStackEntryFlow：它本身就是响应式的，
    // 导航一变化就会重新发射，不需要手动触发重组。
    var canPopBack by remember { mutableStateOf(false) }
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            canPopBack = entry.destination.route != Destination.Read.route
        }
    }
    BackHandler(enabled = canPopBack) {
        navController.popBackStack()
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
        NavHost(
            navController = navController,
            startDestination = Destination.Read.route,
            modifier = Modifier.fillMaxWidth(),
            // Tab 之间的默认切换：短淡入淡出。
            // 不用 navigation 自带的默认过渡 —— 那是「淡入 + 轻微缩放」，
            // 两个页面会叠在一起半透明地晃，详情页返回时尤其难看。
            enterTransition = { fadeIn(animationSpec = tween(200)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
        ) {
            composable(Destination.Read.route) {
                // 读取页入口：先把刚扫到的这张卡抓成快照，成功才跳复制页，
                // 这样用户不必"再贴一次源卡"
                ReadPage(
                    vm,
                    onOpenClone = {
                        if (vm.useOverviewAsCloneSource()) {
                            navController.navigate(CLONE_ROUTE)
                        }
                    },
                )
            }
            composable(
                route = Destination.Write.route,
                // 打开详情页时，写入页向左滑出（平台标准的推入手感）
                exitTransition = {
                    slideOutOfContainer(SlideDirection.Left, animationSpec = tween(260))
                },
                // 从详情页返回时，写入页从左滑回
                popEnterTransition = {
                    slideIntoContainer(SlideDirection.Right, animationSpec = tween(260))
                },
            ) {
                WritePage(
                    vm,
                    onOpenEntry = { entryId ->
                        navController.navigate("write_detail/$entryId")
                    },
                    onOpenClone = { navController.navigate(CLONE_ROUTE) },
                )
            }
            composable(Destination.Console.route) { ConsolePage(vm) }
            composable(Destination.History.route) { HistoryPage(vm) }
            composable(Destination.Settings.route) { SettingsPage(vm) }
            composable(
                route = WRITE_DETAIL_ROUTE,
                // 进入：从右向左滑入；返回：向右滑出。一进一出方向相反，
                // 全程两个页面是「肩并肩」而不是叠在一起。
                enterTransition = {
                    slideIntoContainer(SlideDirection.Left, animationSpec = tween(260))
                },
                popExitTransition = {
                    slideOutOfContainer(SlideDirection.Right, animationSpec = tween(260))
                },
            ) { entry ->
                WriteDetailPage(
                    vm = vm,
                    entryId = entry.arguments?.getString(ARG_ENTRY_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = CLONE_ROUTE,
                // 与详情页一致的推入手感：进从右往左，出从左往右
                enterTransition = {
                    slideIntoContainer(SlideDirection.Left, animationSpec = tween(260))
                },
                popExitTransition = {
                    slideOutOfContainer(SlideDirection.Right, animationSpec = tween(260))
                },
            ) {
                ClonePage(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // 底部导航栏悬浮在内容之上；写入详情页与复制页是全屏任务页，隐藏以免遮挡。
        //
        // ⚠️ 详情页的**实际**路由是 "write_detail/{id}"（占位符已被替换），
        // 而 WRITE_DETAIL_ROUTE 是带占位符的**模板** —— 拿模板去比实际路由
        // 永远不相等，底栏就永远藏不掉。必须用前缀判断。
        val isFullScreenPage = currentRoute?.startsWith("write_detail/") == true ||
            currentRoute == CLONE_ROUTE
        if (!isFullScreenPage) {
            GlassBottomBar(
                currentRoute = currentRoute,
                onSelect = { dest ->
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 全局轻提示（分享解析失败、复制成功等）
        val ui by vm.uiState.collectAsStateWithLifecycle()
        ui.toast?.let { message ->
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(2600)
                vm.clearToast()
            }
            ToastBanner(
                message = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp, start = 20.dp, end = 20.dp),
            )
        }

        // 全局「等待贴近标签」弹窗：所有写入操作共用这一个入口。
        // 写入不再依赖先读一次 —— 点写入后弹窗提示靠近，
        // 检测到标签的瞬间执行写入，成功/失败由各页面自己的结果弹窗汇报。
        if (ui.awaitingTag) {
            AwaitTagDialog(
                label = ui.awaitingWriteLabel,
                writing = ui.busy,
                onCancel = { vm.cancelPendingWrite() },
            )
        }
    }
}

/**
 * 「请靠近 NFC 标签」提示弹窗。
 *
 * 设计成全局对话框而不是页面内的，是因为写入任务可能跨页面存活
 * （用户在写入页点了写入，返回目录时标签才贴上来），
 * 只有挂在应用根部，提示才不会随页面切换而丢失。
 */
@Composable
private fun AwaitTagDialog(
    label: String?,
    writing: Boolean,
    onCancel: () -> Unit,
) {
    val e = extras
    AlertDialog(
        // 写入已启动后不允许取消：指令已经发给卡片，中断没有意义
        onDismissRequest = { if (!writing) onCancel() },
        title = {
            Text(
                if (writing) stringResource(R.string.await_title_writing) else stringResource(R.string.await_title_waiting),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(108.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    RadarPulse(
                        active = !writing,
                        color = e.accent.primary,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Icon(
                        imageVector = Icons.Rounded.Nfc,
                        contentDescription = null,
                        tint = e.accent.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when {
                        writing -> stringResource(R.string.await_msg_writing, label.orEmpty())
                        label != null -> stringResource(R.string.await_msg_waiting_label, label)
                        else -> stringResource(R.string.await_msg_waiting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!writing) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.await_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textTertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, enabled = !writing) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/**
 * 顶部轻提示条。
 */
@Composable
private fun ToastBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(extras.glassSurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = extras.accent.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 悬浮玻璃质感底部导航栏。
 */
@Composable
private fun GlassBottomBar(
    currentRoute: String?,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val e = extras
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(e.glassSurfaceHigh, e.glassSurface)
                )
            )
            .border(androidx.compose.foundation.BorderStroke(1.dp, e.glassBorder), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bottomDestinations.forEach { dest ->
            val selected = currentRoute == dest.route
            NavItem(
                dest = dest,
                selected = selected,
                onClick = { onSelect(dest) },
            )
        }
    }
}

@Composable
private fun NavItem(
    dest: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val e = extras
    val color = if (selected) e.accent.primary else e.textTertiary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) e.accent.primary.copy(alpha = 0.13f) else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = dest.icon,
                contentDescription = stringResource(dest.labelRes),
                modifier = Modifier.size(20.dp),
                tint = color,
            )
            if (selected) {
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(e.accent.primary)
                )
            }
        }
    }
}

