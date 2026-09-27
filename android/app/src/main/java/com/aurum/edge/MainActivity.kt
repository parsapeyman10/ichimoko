package com.aurum.edge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurum.edge.ui.AurumRoot
import com.aurum.edge.ui.AurumViewModel
import com.aurum.edge.ui.AurumViewModelFactory
import com.aurum.edge.ui.theme.AurumTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AurumApplication).container
        // Never start Forex monitoring before the workspace chooser has been shown.
        // A previously running foreground service is handled by its own workspace guard.
        setContent {
            AurumTheme {
                val viewModel: AurumViewModel = viewModel(factory = AurumViewModelFactory(container))
                AurumRoot(viewModel)
            }
        }
    }
}
