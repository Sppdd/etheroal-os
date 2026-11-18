package com.etheroal.os.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import com.etheroal.os.ui.GlassField
import com.etheroal.os.ui.glass
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Clock and date at the top of the home screen; updates every minute. */
@Composable
fun ClockHeader() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(60_000 - value % 60_000) }
    }
    Column(Modifier.padding(top = 24.dp, bottom = 8.dp)) {
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), style = MaterialTheme.typography.displayLarge)
        Text(
            DateFormat.getDateInstance(DateFormat.FULL).format(Date(now)),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Every app, searchable. Tap opens; long-press pins to the dock or shows app info. */
@Composable
fun AppDrawer(catalog: AppCatalog, contentPadding: PaddingValues) {
    val apps by catalog.apps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { catalog.refresh() }
    val shown = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        GlassField(query, { query = it }, "Search apps", Modifier.fillMaxWidth().padding(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.key }) { app -> AppIcon(catalog, app, size = 52, showLabel = true) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppIcon(catalog: AppCatalog, app: AppEntry, size: Int, showLabel: Boolean) {
    val px = with(LocalDensity.current) { size.dp.roundToPx() }
    val icon by produceState<android.graphics.Bitmap?>(null, app.key) {
        value = withContext(Dispatchers.IO) { catalog.icon(app, px) }
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Open ${app.label}",
                    onLongClickLabel = "More options",
                    onClick = { catalog.launch(app) },
                    onLongClick = { menu = true },
                )
                .padding(6.dp),
        ) {
            icon?.let { Image(it.asImageBitmap(), contentDescription = if (showLabel) null else app.label, modifier = Modifier.size(size.dp)) }
                ?: Box(Modifier.size(size.dp))
            if (showLabel) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp).size(width = 76.dp, height = 16.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (catalog.isDocked(app)) "Remove from dock" else "Pin to dock") },
                onClick = { catalog.toggleDock(app); menu = false },
            )
            DropdownMenuItem(text = { Text("App info") }, onClick = { catalog.openAppInfo(app); menu = false })
        }
    }
}
