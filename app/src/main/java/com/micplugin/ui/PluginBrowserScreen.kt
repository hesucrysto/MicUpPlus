package com.micplugin.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import android.content.Intent
import android.widget.Toast
import com.micplugin.plugin.ImportSummary
import com.micplugin.plugin.PluginDescriptor
import com.micplugin.plugin.PluginFormat
import com.micplugin.plugin.PluginImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PluginBrowserScreen(
    navController: NavController,
    vm: AudioViewModel = hiltViewModel(),
) {
    val discovered  by vm.discovered.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("INSTALLED")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }

    val nativePlugins = discovered.filter { it.format != PluginFormat.APK }

    val filtered = when (selectedTab) {
        0 -> nativePlugins
        else -> discovered
    }.filter {
        searchQuery.isEmpty() ||
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.format.name.contains(searchQuery, ignoreCase = true)
    }

    fun importUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            importing = true
            val summary = withContext(Dispatchers.IO) {
                var ok = 0
                var failed = 0
                val errors = mutableListOf<String>()
                uris.forEach { uri ->
                    val result = PluginImporter.importFromUri(context, uri)
                    if (result.success) ok++ else {
                        failed++
                        if (errors.size < 5) errors += result.error
                    }
                }
                ImportSummary(ok, failed, errors)
            }
            vm.rescan()
            Toast.makeText(context, summary.message(), Toast.LENGTH_LONG).show()
            importing = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> importUris(uris) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            scope.launch {
                importing = true
                val summary = withContext(Dispatchers.IO) {
                    PluginImporter.importTree(context, uri)
                }
                vm.rescan()
                Toast.makeText(context, summary.message(), Toast.LENGTH_LONG).show()
                importing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background),
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(StudioColors.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = StudioColors.TextPrimary)
            }
            Text(
                "PLUGIN BROWSER",
                style = MaterialTheme.typography.titleMedium,
                color = StudioColors.TextPrimary,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { folderPicker.launch(null) }, enabled = !importing) {
                Icon(Icons.Default.Folder, "Import plugin folder", tint = StudioColors.Accent)
            }
            IconButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = !importing,
            ) {
                Icon(Icons.Default.Add, "Import plugin file", tint = StudioColors.Accent)
            }
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = StudioColors.Accent,
                )
            }
            IconButton(onClick = { vm.rescan() }) {
                Icon(Icons.Default.Refresh, "Rescan", tint = StudioColors.Accent)
            }
        }

        // ── Search bar ───────────────────────────────────────────────────────
        OutlinedTextField(
            value         = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder   = { Text("Search plugins…", color = StudioColors.TextMuted) },
            leadingIcon   = { Icon(Icons.Default.Search, null, tint = StudioColors.TextMuted) },
            singleLine    = true,
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = StudioColors.Accent,
                unfocusedBorderColor = StudioColors.Border,
                cursorColor          = StudioColors.Accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        // ── Tabs ─────────────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex  = selectedTab,
            containerColor    = StudioColors.Surface,
            contentColor      = StudioColors.Accent,
            indicator = { tabPositions ->
                val pos = tabPositions[selectedTab]
                Box(
                    Modifier
                        .wrapContentSize(Alignment.BottomStart)
                        .offset(x = pos.left)
                        .width(pos.width)
                        .height(2.dp)
                        .background(StudioColors.Accent)
                )
            },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick  = { selectedTab = index },
                    text     = {
                        Text(title, fontSize = 10.sp,
                            color = if (selectedTab == index) StudioColors.Accent else StudioColors.TextMuted)
                    },
                )
            }
        }

        // ── Plugin list ──────────────────────────────────────────────────────
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Extension, null,
                        tint = StudioColors.TextMuted, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No plugins found", color = StudioColors.TextMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Import a plugin file or a plugin folder.\n" +
                        "MicUp++ copies it into app-private storage, so it works on Android 11+ " +
                        "without /Android/data access.",
                        color = StudioColors.TextMuted, fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { folderPicker.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Accent),
                    ) { Text("Import folder") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        border = BorderStroke(1.dp, StudioColors.AccentDim),
                    ) { Text("Import file", color = StudioColors.Accent) }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(filtered, key = { idx, it -> "$idx:${it.id}" }) { _, desc ->
                    PluginBrowserCard(desc, onAdd = {
                        vm.addPlugin(desc)
                        navController.popBackStack()
                    })
                }
            }
        }
    }
}

@Composable
private fun PluginBrowserCard(
    descriptor: PluginDescriptor,
    onAdd: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(8.dp),
        color    = StudioColors.Card,
        border   = BorderStroke(0.5.dp, StudioColors.Border),
    ) {
        Column(Modifier
            .clickable { expanded = !expanded }
            .padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Format icon
                val formatColor = Color(descriptor.format.colorHex)
                Box(
                    Modifier
                        .size(36.dp)
                        .background(formatColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .border(0.5.dp, formatColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(descriptor.format.name.first().toString(),
                        color = formatColor, fontSize = 14.sp,
                        style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(descriptor.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PluginFormatBadge(descriptor.format)
                        if (descriptor.params.isNotEmpty()) {
                            Text("${descriptor.params.size} params",
                                fontSize = 8.sp, color = StudioColors.TextMuted)
                        }
                    }
                }
                Button(
                    onClick = onAdd,
                    colors  = ButtonDefaults.buttonColors(containerColor = StudioColors.Accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", fontSize = 11.sp)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = StudioColors.Border)
                Spacer(Modifier.height(8.dp))
                Text(descriptor.path, fontSize = 9.sp, color = StudioColors.TextMuted)
                if (descriptor.description.isNotEmpty()) {
                    Text(descriptor.description, fontSize = 10.sp, color = StudioColors.TextMuted)
                }
                if (descriptor.format == PluginFormat.VST3) {
                    Text("⚠ VST3 support is experimental. Use LV2/CLAP for reliability.",
                        fontSize = 10.sp, color = StudioColors.MeterAmber)
                }
            }
        }
    }
}


