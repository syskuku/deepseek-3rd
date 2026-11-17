package com.dschat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dschat.app.ui.ApiKeyScreen
import com.dschat.app.ui.ChatScreen
import com.dschat.app.ui.ChatViewModel
import com.dschat.app.ui.SettingsScreen
import com.dschat.app.ui.UpdateDialog
import com.dschat.app.ui.theme.DeepSeekTheme
import com.dschat.app.ui.theme.LocalDsColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ChatViewModel = viewModel()
            val theme by vm.theme.collectAsState()
            val hasKey by vm.hasApiKey.collectAsState()
            var showSettings by remember { mutableStateOf(false) }
            val snackbar = remember { SnackbarHostState() }

            LaunchedEffect(Unit) {
                vm.toast.collect { snackbar.showSnackbar(it) }
            }

            // 启动时静默检查更新（有 Key 后）
            LaunchedEffect(hasKey) {
                if (hasKey) vm.checkUpdate(manual = false)
            }

            DeepSeekTheme(theme) {
                val ds = LocalDsColors.current
                Scaffold(
                    containerColor = ds.bgBase,
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        when {
                            !hasKey -> ApiKeyScreen(
                                onVerified = { key -> vm.verifyAndSaveApiKey(key) },
                            )
                            showSettings -> SettingsScreen(vm, onBack = { showSettings = false })
                            else -> ChatScreen(vm, onOpenSettings = { showSettings = true })
                        }
                    }
                }
                UpdateDialog(vm)
            }
        }
    }
}