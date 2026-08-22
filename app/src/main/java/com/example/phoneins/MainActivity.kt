package com.example.phoneins

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.example.phoneins.service.NavigationService
import com.example.phoneins.ui.PhoneInsApp
import com.example.phoneins.ui.PhoneInsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneInsTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { }
                val csvLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    sendServiceAction(
                        this@MainActivity,
                        NavigationService.ACTION_CSV_REPLAY_IMPORT,
                        mapOf(NavigationService.EXTRA_SOURCE_URI to uri.toString()),
                    )
                }
                LaunchedEffect(Unit) {
                    val needed = buildList {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
                }
                PhoneInsApp(
                    sendAction = { action, extras -> sendServiceAction(this@MainActivity, action, extras) },
                    chooseCsv = {
                        csvLauncher.launch(
                            arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"),
                        )
                    },
                )
            }
        }
    }
}

private fun sendServiceAction(context: Context, action: String, extras: Map<String, Any>) {
    val intent = Intent(context, NavigationService::class.java).setAction(action)
    extras.forEach { (key, value) ->
        when (value) {
            is Boolean -> intent.putExtra(key, value)
            is Double -> intent.putExtra(key, value)
            is String -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
        }
    }
    if (action == NavigationService.ACTION_STOP) context.stopService(intent)
    else ContextCompat.startForegroundService(context, intent)
}
