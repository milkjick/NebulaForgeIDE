package com.nebulaforge.app.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.pty.PtySession
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TerminalViewModel : ViewModel() {
    private var pty: PtySession? = null
    private val _text = mutableStateOf("")
    val text: State<String> = _text

    fun start(context: Context) {
        if (pty != null) return
        val env = mapOf(
            "HOME" to Environment.homeRoot(context),
            "PREFIX" to Environment.usrRoot(context),
            "TMPDIR" to Environment.tmpDir(context),
            "PATH" to "${Environment.binDir(context)}:/system/bin:/system/xbin",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor"
        )
        val session = PtySession("${Environment.binDir(context)}/bash", env)
        pty = session
        session.start()
        viewModelScope.launch { session.output.collectLatest { _text.value += it } }
    }
    fun write(s: String) { pty?.write(s) }
    override fun onCleared() { pty?.close() }
}

@Composable
fun TerminalScreen(vm: TerminalViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.start(context) }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(vm.text.value) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().background(Color(0xFF101114)).padding(8.dp)) {
        Text(
            vm.text.value,
            color = Color(0xFFE6E6E6),
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White
                )
            )
            Button(onClick = { vm.write(input + "\n"); input = "" }) { Text("执行") }
        }
    }
}
