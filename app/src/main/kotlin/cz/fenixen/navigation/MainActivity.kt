package cz.fenixen.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.data.MapTheme
import cz.fenixen.navigation.ui.MainViewModel
import cz.fenixen.navigation.ui.NavigationApp
import cz.fenixen.navigation.ui.theme.NavTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var hasLocationPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || checkLocation()
        if (hasLocationPermission) vm.onLocationPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasLocationPermission = checkLocation()
        if (!hasLocationPermission) requestPermissions()

        // GPS jen když je appka vidět (nebo běží navigační služba / Android Auto).
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var release: (() -> Unit)? = null
            override fun onStart(owner: LifecycleOwner) {
                release = vm.acquireLocation()
            }

            override fun onStop(owner: LifecycleOwner) {
                release?.invoke()
                release = null
            }
        })

        // Při navigaci nenecháme zhasnout displej.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.navState.map { it is NavigationState.Navigating }.distinctUntilChanged().collect { navigating ->
                    if (navigating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        handleIntent(intent)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = when (settings.mapTheme) {
                MapTheme.AUTO -> isSystemInDarkTheme()
                MapTheme.DAY -> false
                MapTheme.NIGHT -> true
            }
            NavTheme(dark = dark) {
                NavigationApp(vm, dark, hasLocationPermission, onRequestPermission = ::requestPermissions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "geo") vm.handleGeoUri(data.toString())
    }

    private fun checkLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
