package com.whispertype.android.ui.dictionary

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.R
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.ui.theme.StudioCard
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioCta
import com.whispertype.android.ui.theme.StudioPageTitle
import com.whispertype.android.ui.theme.StudioType
import kotlinx.coroutines.launch

@Composable
fun DictionaryScreen(
    settings: SettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val dictionary by settings.dictionary.collectAsStateWithLifecycle(initialValue = emptyList())
    var adding by remember { mutableStateOf(false) }
    var dictionaryWord by remember { mutableStateOf("") }
    var dictionaryReplacement by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StudioPageTitle(stringResource(R.string.nav_dictionary), modifier = Modifier.padding(top = 6.dp))
        Text(
            text = stringResource(R.string.settings_dictionary_people_desc),
            style = StudioType.why,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        dictionary.forEach { entry ->
            StudioCard(radius = 14) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = entry.match, style = StudioType.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    Text(text = "→", style = StudioType.rowDesc)
                    Text(
                        text = entry.replace.ifBlank { entry.match },
                        style = StudioType.snippet,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        style = StudioType.rowDesc.copy(color = StudioColors.OnSurfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable {
                            scope.launch { settings.removeDictionaryEntry(entry.match) }
                        },
                    )
                }
            }
        }
        if (adding) {
            StudioCard(radius = 14) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dictionaryWord,
                        onValueChange = { dictionaryWord = it },
                        label = { Text(stringResource(R.string.settings_dictionary_word_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dictionaryReplacement,
                        onValueChange = { dictionaryReplacement = it },
                        label = { Text(stringResource(R.string.settings_dictionary_replacement_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StudioCta(
                        text = stringResource(R.string.settings_dictionary_add),
                        onClick = {
                            val word = dictionaryWord.trim()
                            if (word.isNotEmpty()) {
                                scope.launch {
                                    settings.addDictionaryEntry(DictionaryEntry(word, dictionaryReplacement.trim()))
                                }
                                dictionaryWord = ""
                                dictionaryReplacement = ""
                                adding = false
                            }
                        },
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.settings_dictionary_add_word),
                style = StudioType.cta.copy(color = StudioColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, StudioColors.Hairline, RoundedCornerShape(14.dp))
                    .clickable { adding = true }
                    .padding(12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (dictionary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_dictionary_clear),
                style = StudioType.rowDesc,
                modifier = Modifier
                    .clickable { scope.launch { settings.clearDictionary() } }
                    .padding(4.dp),
            )
        }
    }
}
