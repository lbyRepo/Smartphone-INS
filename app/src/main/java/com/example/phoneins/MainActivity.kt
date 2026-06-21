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
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { }
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
                    if (needed.isNotEmpty()) launcher.launch(needed.toTypedArray())
                }
                PhoneInsApp { action, extras -> sendServiceAction(this@MainActivity, action, extras) }
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
        }
    }
    if (action == NavigationService.ACTION_STOP) context.stopService(intent)
    else ContextCompat.startForegroundService(context, intent)
}
