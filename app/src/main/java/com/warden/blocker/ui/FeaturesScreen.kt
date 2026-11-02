package com.warden.blocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.feature.FeatureCatalog

@Composable
fun FeaturesScreen(vm: WardenViewModel, onBack: () -> Unit) {
    val enabled by vm.enabledFeatureKeys.collectAsStateWithLifecycle()
    val gutter = pageGutter()

    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Block in-app feeds", onBack = onBack, showDone = true)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = gutter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                IntroText(
                    "Block just the endless-scroll parts of apps you still want to use — Reels, Shorts, " +
                        "For You — while the rest keeps working. Needs the accessibility permission and " +
                        "blocking switched on.",
                    Modifier.padding(bottom = 8.dp),
                )
            }
            items(FeatureCatalog.ALL, key = { it.key }) { feature ->
                SwitchCard(
                    feature.label,
                    feature.description,
                    checked = feature.key in enabled,
                    onCheckedChange = { vm.setFeatureEnabled(feature.key, it) },
                )
            }
        }
    }
}
