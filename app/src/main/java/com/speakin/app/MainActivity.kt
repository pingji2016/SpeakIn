package com.speakin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import android.util.Log
import androidx.navigation.compose.rememberNavController
import com.speakin.app.domain.service.ModelServiceFacade
import com.speakin.app.ui.navigation.SpeakInNavGraph
import com.speakin.app.ui.theme.SpeakInTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var modelService: ModelServiceFacade

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // DEBUG: 启动时绑定模型服务，触发测试转写
        Log.i("MainActivity", "Binding ModelService for test...")
        modelService.bind()

        setContent {
            SpeakInTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    SpeakInNavGraph(navController = navController)
                }
            }
        }
    }
}
