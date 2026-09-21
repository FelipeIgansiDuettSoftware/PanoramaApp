package com.panoramaapp

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.panoramaapp.panorama.PanoramaViewModel
import com.panoramaapp.panorama.camera.CameraXController
import com.panoramaapp.ui.PanoramaApp
import com.panoramaapp.ui.theme.PanoramaAppTheme

class MainActivity : ComponentActivity() {
    private lateinit var panoramaViewModel: PanoramaViewModel
    private lateinit var cameraController: CameraXController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()


        WindowCompat.setDecorFitsSystemWindows(window, true)

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        panoramaViewModel = ViewModelProvider(this)[PanoramaViewModel::class.java]
        cameraController = CameraXController(this)
        setContent {
            PanoramaAppTheme(darkTheme = false, ) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        PanoramaApp(panoramaViewModel, cameraController)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        cameraController.shutdown()
        super.onDestroy()
    }
}
