package com.shady.earphonefinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.shady.earphonefinder.ui.DeviceListScreen
import com.shady.earphonefinder.ui.HuntScreen
import com.shady.earphonefinder.ui.PermissionScreen

class MainActivity : ComponentActivity() {

    private val vm: FinderViewModel by viewModels()
    private var granted by mutableStateOf(false)
    private var askedMotion = false

    private val needed: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // Step counting for walk-to-calibrate. Optional: without it we still show distance.
    private val motion: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray()

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasAll() = needed.all { has(it) }
    private fun syncMotion() { vm.stepsGranted = motion.all { has(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Searching takes a minute; don't let the screen sleep mid-hunt.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        granted = hasAll()

        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(color = com.shady.earphonefinder.ui.Look.Bg) {
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        granted = hasAll()
                        syncMotion()
                        if (granted) vm.resume()
                    }
                    // already had Bluetooth from an older build: ask for motion once
                    LaunchedEffect(granted) {
                        if (granted && !vm.stepsGranted && motion.isNotEmpty() && !askedMotion) {
                            askedMotion = true
                            launcher.launch(motion)
                        }
                    }
                    when {
                        !granted -> PermissionScreen(
                            oldAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
                            onRequest = { launcher.launch(needed + motion) },
                        )
                        vm.target == null -> DeviceListScreen(vm)
                        else -> HuntScreen(vm)   // details is drawn on top of the hunt glow
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        granted = hasAll()
        syncMotion()
        if (granted) vm.resume()
    }

    override fun onStop() {
        vm.pause()
        super.onStop()
    }
}
