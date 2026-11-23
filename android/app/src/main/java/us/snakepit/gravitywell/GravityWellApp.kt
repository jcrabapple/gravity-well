package us.snakepit.gravitywell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import us.snakepit.gravitywell.cache.GravityWellDbHelper
import us.snakepit.gravitywell.cache.OfflineCacheStore
import us.snakepit.gravitywell.security.TokenVault
import us.snakepit.gravitywell.theme.GravityWellTheme
import us.snakepit.gravitywell.ui.shell.GravityWellAdaptiveShell
import us.snakepit.gravitywell.ui.shell.PendingDeepLink
import us.snakepit.gravitywell.ui.shell.ShellController

/**
 * App-scoped dependency holder lazily initialized from applicationContext so
 * SQLite WAL OfflineCacheStore and AndroidKeyStore TokenVault are shared safely.
 */
class GravityWellRuntime(
    val dbHelper: GravityWellDbHelper,
    val cacheStore: OfflineCacheStore,
    val tokenVault: TokenVault,
) {
    companion object {
        @Volatile
        private var instance: GravityWellRuntime? = null

        fun get(context: Context): GravityWellRuntime {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: run {
                    val appCtx = context.applicationContext
                    val db = GravityWellDbHelper(appCtx)
                    val cache = OfflineCacheStore(db)
                    val vault = TokenVault(appCtx)
                    GravityWellRuntime(db, cache, vault).also { instance = it }
                }
            }
        }
    }
}

class GravityWellShellViewModel(
    runtime: GravityWellRuntime,
) : ViewModel() {
    val controller: ShellController = ShellController(
        cacheStore = runtime.cacheStore,
        tokenVault = runtime.tokenVault,
        scope = viewModelScope,
    )

    override fun onCleared() {
        controller.shutdown()
        super.onCleared()
    }

    class Factory(private val runtime: GravityWellRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GravityWellShellViewModel::class.java))
            return GravityWellShellViewModel(runtime) as T
        }
    }
}

@Composable
fun GravityWellApp(
    pendingDeepLink: PendingDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    explicitController: ShellController? = null,
) {
    val context = LocalContext.current
    val controller = explicitController ?: run {
        val vm: GravityWellShellViewModel = viewModel(
            factory = GravityWellShellViewModel.Factory(GravityWellRuntime.get(context)),
        )
        vm.controller
    }

    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null) {
            controller.consumeDeepLink(pendingDeepLink)
            onDeepLinkConsumed()
        }
    }

    GravityWellTheme(dynamicColor = false) {
        GravityWellAdaptiveShell(controller = controller)
    }
}
