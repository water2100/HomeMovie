package com.example.localmovielibrary.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.localmovielibrary.data.AppContainer
import com.example.localmovielibrary.data.repository.AppUpdateInfo
import com.example.localmovielibrary.ui.cloud.CloudBrowserScreen
import com.example.localmovielibrary.ui.cloud.CloudBrowserViewModel
import com.example.localmovielibrary.ui.detail.DetailScreen
import com.example.localmovielibrary.ui.detail.DetailViewModel
import com.example.localmovielibrary.ui.favorites.FavoritesScreen
import com.example.localmovielibrary.ui.favorites.FavoritesViewModel
import com.example.localmovielibrary.ui.filter.FilterResultScreen
import com.example.localmovielibrary.ui.filter.FilterResultViewModel
import com.example.localmovielibrary.ui.home.HomeScreen
import com.example.localmovielibrary.ui.home.HomeImageMode
import com.example.localmovielibrary.ui.home.HomeViewModel
import com.example.localmovielibrary.ui.home.MoviesLibraryScreen
import com.example.localmovielibrary.ui.logs.ScrapeLogScreen
import com.example.localmovielibrary.ui.logs.ScrapeLogViewModel
import com.example.localmovielibrary.ui.player.PlayerScreen
import com.example.localmovielibrary.ui.player.PlayerViewModel
import com.example.localmovielibrary.ui.search.SearchScreen
import com.example.localmovielibrary.ui.search.SearchViewModel
import com.example.localmovielibrary.ui.settings.SettingsScreen
import com.example.localmovielibrary.ui.settings.SettingsViewModel
import kotlinx.coroutines.delay

@Composable
fun LocalMovieLibraryAppRoot(appContainer: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val unfinishedMovieScrapeTaskCount by appContainer.movieRepository
        .observeUnfinishedScrapeTaskCount()
        .collectAsState(initial = 0)
    val unfinishedFolderBatchTaskCount by appContainer.cloudFolderBatchTaskRepository
        .observeUnfinishedTaskCount()
        .collectAsState(initial = 0)
    val unfinishedScrapeTaskCount = unfinishedMovieScrapeTaskCount + unfinishedFolderBatchTaskCount
    val latestUnfinishedScrapeTaskCount by rememberUpdatedState(unfinishedScrapeTaskCount)
    var scrapeTaskPromptDismissed by remember { mutableStateOf(false) }
    var showStartupScrapeTaskPrompt by remember { mutableStateOf(false) }
    var startupUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    val showBottomBar = currentRoute == Route.Home ||
        currentRoute == Route.Movies ||
        currentRoute == Route.MovieLibrary ||
        currentRoute == Route.FilterResult ||
        currentRoute == Route.Favorites ||
        currentRoute == Route.Search ||
        currentRoute == Route.CloudBrowser ||
        currentRoute == Route.Settings ||
        currentRoute == Route.SettingsUpdate ||
        currentRoute == Route.SettingsScrapeTasks

    LaunchedEffect(Unit) {
        appContainer.appUpdateRepository.cleanupInstalledUpdateApksIfNeeded()
        if (!appContainer.settingsRepository.isUpdateAutoCheckOnStartupEnabled()) return@LaunchedEffect
        delay(8_000)
        if (!appContainer.settingsRepository.isUpdateAutoCheckOnStartupEnabled()) return@LaunchedEffect
        runCatching { appContainer.appUpdateRepository.checkForUpdate() }
            .onSuccess { result ->
                if (result.hasUpdate) {
                    startupUpdateInfo = result.latest
                }
            }
    }

    LaunchedEffect(Unit) {
        delay(800)
        if (latestUnfinishedScrapeTaskCount > 0) {
            showStartupScrapeTaskPrompt = true
        }
    }

    Scaffold(
        containerColor = Color(0xFF070A0E),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                AppBottomNavigation(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Route.Home) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .background(Color(0xFF070A0E))
                .padding(innerPadding)
        ) {
            NavHost(navController = navController, startDestination = Route.Home) {
                composable(Route.Home) {
                    val viewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(
                            repository = appContainer.movieRepository,
                            domesticMovieRepository = appContainer.domesticMovieRepository,
                            settingsRepository = appContainer.settingsRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            playbackProgressRepository = appContainer.playbackProgressRepository
                        )
                    )
                    HomeScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        onPlay = { movie -> navController.navigate(Route.player(movie.videoUri, movie.title, movie.videoName)) },
                        onOpenLibrary = { navController.navigate(Route.Movies) }
                    )
                }
                composable(Route.Movies) {
                    val viewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(
                            repository = appContainer.movieRepository,
                            domesticMovieRepository = appContainer.domesticMovieRepository,
                            settingsRepository = appContainer.settingsRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            playbackProgressRepository = appContainer.playbackProgressRepository
                        )
                    )
                    MoviesLibraryScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        onPlay = { movie -> navController.navigate(Route.player(movie.videoUri, movie.title, movie.videoName)) },
                        onDomesticPlay = { movie, source ->
                            navController.navigate(Route.player("cloud115://play/${source.videoPickcode}", movie.movie.folderName, source.videoName))
                        },
                        onFilterClick = { filterType, filterValue ->
                            navController.navigate(Route.filterResult(filterType, filterValue))
                        }
                    )
                }
                composable(Route.Search) {
                    val viewModel: SearchViewModel = viewModel(
                        factory = SearchViewModel.factory(appContainer.movieRepository)
                    )
                    SearchScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        imageMode = appContainer.homeImageMode()
                    )
                }
                composable(Route.CloudBrowser) {
                    val viewModel: CloudBrowserViewModel = viewModel(
                        factory = CloudBrowserViewModel.factory(
                            strmRepository = appContainer.cloud115StrmRepository,
                            recordRepository = appContainer.cloudStrmRecordRepository,
                            settingsRepository = appContainer.settingsRepository,
                            movieRepository = appContainer.movieRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            domesticMovieRepository = appContainer.domesticMovieRepository,
                            folderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                            folderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner
                        )
                    )
                    CloudBrowserScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onPlayVideo = { item ->
                            val pickcode = item.pickcode ?: return@CloudBrowserScreen
                            navController.navigate(Route.player("cloud115://play/$pickcode", item.name, item.name))
                        },
                        onMovieAdded = { }
                    )
                }
                composable(Route.Settings) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            repository = appContainer.settingsRepository,
                            movieRepository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            appUpdateRepository = appContainer.appUpdateRepository,
                            cloud115QrLoginClient = appContainer.cloud115QrLoginClient,
                            cloudFolderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                            cloudFolderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner
                        )
                    )
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenScrapeLogs = { navController.navigate(Route.ScrapeLogs) }
                    )
                }
                composable(Route.SettingsUpdate) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            repository = appContainer.settingsRepository,
                            movieRepository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            appUpdateRepository = appContainer.appUpdateRepository,
                            cloud115QrLoginClient = appContainer.cloud115QrLoginClient,
                            cloudFolderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                            cloudFolderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner
                        )
                    )
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenScrapeLogs = { navController.navigate(Route.ScrapeLogs) },
                        openUpdatePage = true,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.SettingsScrapeTasks) {
                    val viewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            repository = appContainer.settingsRepository,
                            movieRepository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            appUpdateRepository = appContainer.appUpdateRepository,
                            cloud115QrLoginClient = appContainer.cloud115QrLoginClient,
                            cloudFolderBatchTaskRepository = appContainer.cloudFolderBatchTaskRepository,
                            cloudFolderBatchTaskRunner = appContainer.cloudFolderBatchTaskRunner
                        )
                    )
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenScrapeLogs = { navController.navigate(Route.ScrapeLogs) },
                        openScrapeTasksPage = true,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.ScrapeLogs) {
                    val viewModel: ScrapeLogViewModel = viewModel(
                        factory = ScrapeLogViewModel.factory(appContainer.strmScrapeRepository)
                    )
                    ScrapeLogScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.Favorites) {
                    val viewModel: FavoritesViewModel = viewModel(
                        factory = FavoritesViewModel.factory(appContainer.movieRepository)
                    )
                    FavoritesScreen(
                        viewModel = viewModel,
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        imageMode = appContainer.homeImageMode()
                    )
                }
                composable(
                    route = Route.Detail,
                    arguments = listOf(navArgument("movieId") { type = NavType.LongType })
                ) { entry ->
                    val movieId = entry.arguments?.getLong("movieId") ?: return@composable
                    val viewModel: DetailViewModel = viewModel(
                        key = "detail-$movieId",
                        factory = DetailViewModel.factory(
                            movieId = movieId,
                            repository = appContainer.movieRepository,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            scrapeRepository = appContainer.strmScrapeRepository,
                            settingsRepository = appContainer.settingsRepository
                        )
                    )
                    DetailScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onPlay = { videoUri, title, fileName -> navController.navigate(Route.player(videoUri, title, fileName)) },
                        onFilterClick = { filterType, filterValue ->
                            navController.navigate(Route.filterResult(filterType, filterValue))
                        },
                        onMovieClick = { similarMovieId -> navController.navigate(Route.detail(similarMovieId)) },
                        onOpenMovie = { newMovieId ->
                            navController.popBackStack()
                            navController.navigate(Route.detail(newMovieId))
                        }
                    )
                }
                composable(
                    route = Route.FilterResult,
                    arguments = listOf(
                        navArgument("filterType") { type = NavType.StringType },
                        navArgument("filterValue") { type = NavType.StringType }
                    )
                ) { entry ->
                    val filterType = entry.arguments?.getString("filterType").orEmpty()
                    val filterValue = Uri.decode(entry.arguments?.getString("filterValue").orEmpty())
                    val viewModel: FilterResultViewModel = viewModel(
                        key = "filter-$filterType-$filterValue",
                        factory = FilterResultViewModel.factory(filterType, filterValue, appContainer.movieRepository)
                    )
                    FilterResultScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onMovieClick = { movieId -> navController.navigate(Route.detail(movieId)) },
                        imageMode = appContainer.homeImageMode()
                    )
                }
                composable(
                    route = Route.Player,
                    arguments = listOf(
                        navArgument("videoUri") { type = NavType.StringType },
                        navArgument("title") {
                            type = NavType.StringType
                            defaultValue = "Movie"
                        },
                        navArgument("fileName") {
                            type = NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { entry ->
                    val videoUri = entry.arguments?.getString("videoUri") ?: return@composable
                    val title = entry.arguments?.getString("title") ?: "Movie"
                    val fileName = entry.arguments?.getString("fileName").orEmpty()
                    val parsedUri = Uri.parse(videoUri)
                    val application = LocalContext.current.applicationContext as android.app.Application
                    val viewModel: PlayerViewModel = viewModel(
                        key = "player-$videoUri",
                        factory = PlayerViewModel.factory(
                            application = application,
                            videoUri = parsedUri,
                            title = title,
                            fileName = fileName,
                            directLinkRepository = appContainer.directLinkRepository,
                            cloud115Client = appContainer.cloud115Client,
                            cloudStrmRecordRepository = appContainer.cloudStrmRecordRepository,
                            settingsRepository = appContainer.settingsRepository,
                            playbackProgressRepository = appContainer.playbackProgressRepository
                        )
                    )
                    PlayerScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }

    if (showStartupScrapeTaskPrompt && unfinishedScrapeTaskCount > 0 && !scrapeTaskPromptDismissed) {
        AlertDialog(
            onDismissRequest = { scrapeTaskPromptDismissed = true },
            title = { Text("有未完成任务") },
            text = {
                Text(
                    buildString {
                        append("检测到您有未完成任务")
                        if (unfinishedMovieScrapeTaskCount > 0 || unfinishedFolderBatchTaskCount > 0) {
                            append("：")
                            val parts = mutableListOf<String>()
                            if (unfinishedMovieScrapeTaskCount > 0) {
                                parts += "影片刮削 $unfinishedMovieScrapeTaskCount 个"
                            }
                            if (unfinishedFolderBatchTaskCount > 0) {
                                parts += "网盘文件夹任务 $unfinishedFolderBatchTaskCount 个"
                            }
                            append(parts.joinToString("，"))
                        }
                        append("。请挂节点后到刮削任务中手动启动，App 不会自动继续。")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scrapeTaskPromptDismissed = true
                        navController.navigate(Route.SettingsScrapeTasks) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                ) {
                    Text("去刮削任务")
                }
            },
            dismissButton = {
                TextButton(onClick = { scrapeTaskPromptDismissed = true }) {
                    Text("稍后处理")
                }
            }
        )
    }

    startupUpdateInfo?.let { update ->
        AlertDialog(
            onDismissRequest = { startupUpdateInfo = null },
            title = { Text("发现新版本 ${update.versionName}") },
            text = {
                Text(
                    buildString {
                        append("当前版本可以更新到 ${update.versionName}。")
                        update.sizeBytes?.let { size ->
                            append("\nAPK 大小：${formatUpdateSize(size)}")
                        }
                        if (update.notes.isNotEmpty()) {
                            append("\n\n")
                            append(update.notes.take(5).joinToString("\n") { "- $it" })
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        startupUpdateInfo = null
                        navController.navigate(Route.SettingsUpdate) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                ) {
                    Text("去更新")
                }
            },
            dismissButton = {
                TextButton(onClick = { startupUpdateInfo = null }) {
                    Text("稍后")
                }
            }
        )
    }
}

private fun AppContainer.homeImageMode(): HomeImageMode =
    settingsRepository.getHomeImageModeName()
        ?.let { stored -> HomeImageMode.entries.firstOrNull { it.name == stored } }
        ?: HomeImageMode.Poster

@Composable
private fun AppBottomNavigation(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF0B1016),
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = currentRoute == Route.Home,
            onClick = { onNavigate(Route.Home) },
            icon = { Icon(Icons.Rounded.Home, contentDescription = "首页") },
            label = { Text("\u9996\u9875") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Movies ||
                currentRoute == Route.MovieLibrary ||
                currentRoute == Route.FilterResult,
            onClick = { onNavigate(Route.Movies) },
            icon = { Icon(Icons.Rounded.Movie, contentDescription = "影片") },
            label = { Text("\u5F71\u7247") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Favorites,
            onClick = { onNavigate(Route.Favorites) },
            icon = { Icon(Icons.Rounded.Favorite, contentDescription = "收藏") },
            label = { Text("\u6536\u85CF") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Search,
            onClick = { onNavigate(Route.Search) },
            icon = { Icon(Icons.Rounded.Search, contentDescription = "搜索") },
            label = { Text("\u641C\u7D22") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.CloudBrowser,
            onClick = { onNavigate(Route.CloudBrowser) },
            icon = { Icon(Icons.Rounded.Cloud, contentDescription = "网盘") },
            label = { Text("\u7F51\u76D8") },
            colors = bottomNavColors()
        )
        NavigationBarItem(
            selected = currentRoute == Route.Settings ||
                currentRoute == Route.SettingsUpdate ||
                currentRoute == Route.SettingsScrapeTasks,
            onClick = { onNavigate(Route.Settings) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = "设置") },
            label = { Text("\u8BBE\u7F6E") },
            colors = bottomNavColors()
        )
    }
}

@Composable
private fun bottomNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color.Black,
    selectedTextColor = Color.White,
    indicatorColor = Color.White,
    unselectedIconColor = Color.White.copy(alpha = 0.58f),
    unselectedTextColor = Color.White.copy(alpha = 0.58f)
)

private object Route {
    const val Home = "home"
    const val Movies = "movies"
    const val MovieLibrary = "movieLibrary"
    const val Favorites = "favorites"
    const val Search = "search"
    const val CloudBrowser = "cloudBrowser"
    const val Settings = "settings"
    const val SettingsUpdate = "settings/update"
    const val SettingsScrapeTasks = "settings/scrapeTasks"
    const val ScrapeLogs = "scrapeLogs"
    const val Detail = "movieDetail/{movieId}"
    const val FilterResult = "filterResult/{filterType}/{filterValue}"
    const val Player = "player/{videoUri}?title={title}&fileName={fileName}"

    fun detail(movieId: Long) = "movieDetail/$movieId"

    fun filterResult(filterType: String, filterValue: String) =
        "filterResult/${Uri.encode(filterType)}/${Uri.encode(filterValue)}"

    fun player(videoUri: String, title: String, fileName: String) =
        "player/${Uri.encode(videoUri)}?title=${Uri.encode(title)}&fileName=${Uri.encode(fileName)}"
}

private fun formatUpdateSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}
