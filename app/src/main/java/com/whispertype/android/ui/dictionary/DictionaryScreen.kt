package com.whispertype.android.ui.dictionary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whispertype.android.R
import com.whispertype.android.core.dictionary.DictionaryEntry
import com.whispertype.android.data.settings.SettingsRepository
import com.whispertype.android.ui.theme.StudioCard
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioLayout
import com.whispertype.android.ui.theme.StudioPageTitle
import com.whispertype.android.ui.theme.StudioType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(
    settings: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val dictionary by settings.dictionary.collectAsStateWithLifecycle(initialValue = emptyList())

    var sheetOpen by remember { mutableStateOf(false) }
    var entryBeingEdited by remember { mutableStateOf<DictionaryEntry?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StudioLayout.GutterHorizontal, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(StudioLayout.SpacingRelated),
    ) {
        StudioPageTitle(
            stringResource(R.string.dictionary_title),
            modifier = Modifier.padding(top = 2.dp, bottom = 0.dp),
        )

        Text(
            text = stringResource(R.string.dictionary_description),
            style = StudioType.why,
        )

        // Example callout card
        StudioCard(radius = StudioLayout.RadiusField) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.dictionary_example_callout),
                    style = StudioType.rowDesc.copy(color = StudioColors.OnBackground),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Saved rules list
        dictionary.forEach { entry ->
            StudioCard(
                radius = StudioLayout.RadiusCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dictionary_tag_you_say),
                            style = StudioType.tagLabel,
                        )
                        Text(
                            text = entry.match,
                            style = StudioType.recentSnippet,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dictionary_tag_it_writes),
                            style = StudioType.tagLabel,
                        )
                        if (entry.replace.isBlank()) {
                            Text(
                                text = stringResource(R.string.dictionary_needs_replacement),
                                style = StudioType.rowDesc.copy(
                                    color = StudioColors.ErrorOnBanner,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        } else {
                            Text(
                                text = entry.replace,
                                style = StudioType.recentSnippet.copy(
                                    color = StudioColors.Accent,
                                ),
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = {
                                entryBeingEdited = entry
                                sheetOpen = true
                            },
                            modifier = Modifier.size(StudioLayout.MinInteractiveTarget),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.dictionary_action_edit),
                                tint = StudioColors.OnSurfaceVariant,
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch { settings.removeDictionaryEntry(entry.match) }
                            },
                            modifier = Modifier.size(StudioLayout.MinInteractiveTarget),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.dictionary_action_delete),
                                tint = StudioColors.OnSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Add rule button (filled 52dp mint)
        Button(
            onClick = {
                entryBeingEdited = null
                sheetOpen = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(StudioLayout.RadiusField),
            colors = ButtonDefaults.buttonColors(
                containerColor = StudioColors.Accent,
                contentColor = StudioColors.OnAccent,
            ),
        ) {
            Text(
                text = stringResource(R.string.dictionary_add_rule),
                style = StudioType.cta,
            )
        }

        if (dictionary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.dictionary_action_clear_all),
                    style = StudioType.rowDesc.copy(color = StudioColors.OnSurfaceVariant),
                    modifier = Modifier
                        .clickable { showClearConfirmation = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(StudioLayout.GutterBottom))
    }

    if (sheetOpen) {
        DictionaryRuleBottomSheet(
            sheetState = sheetState,
            entry = entryBeingEdited,
            onDismiss = { sheetOpen = false },
            onSave = { match, replace ->
                scope.launch {
                    val original = entryBeingEdited
                    if (original != null) {
                        settings.updateDictionaryEntry(
                            originalMatch = original.match,
                            newEntry = DictionaryEntry(match, replace),
                        )
                    } else {
                        settings.addDictionaryEntry(DictionaryEntry(match, replace))
                    }
                    sheetOpen = false
                }
            },
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = {
                Text(
                    text = stringResource(R.string.dictionary_clear_confirm_title),
                    style = StudioType.rowTitle,
                )
            },
            text = {
                Text(
                    text = pluralStringResource(
                        R.plurals.dictionary_clear_confirm_message,
                        dictionary.size,
                        dictionary.size,
                    ),
                    style = StudioType.why,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { settings.clearDictionary() }
                        showClearConfirmation = false
                    },
                ) {
                    Text(
                        text = stringResource(R.string.dictionary_clear_confirm_action),
                        color = StudioColors.ErrorOnBanner,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(
                        text = stringResource(R.string.dictionary_action_cancel),
                        color = StudioColors.OnSurfaceVariant,
                    )
                }
            },
            containerColor = StudioColors.Surface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryRuleBottomSheet(
    sheetState: SheetState,
    entry: DictionaryEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var sayText by remember(entry) { mutableStateOf(entry?.match ?: "") }
    var writeText by remember(entry) { mutableStateOf(entry?.replace ?: "") }

    val trimmedSay = sayText.trim()
    val trimmedWrite = writeText.trim()
    val isComplete = trimmedSay.isNotEmpty() && trimmedWrite.isNotEmpty()
    val isIdentical = isComplete && trimmedSay.equals(trimmedWrite, ignoreCase = true)

    val writeFocusRequester = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = StudioColors.Surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(
                    if (entry == null) R.string.dictionary_sheet_add_title else R.string.dictionary_sheet_edit_title,
                ),
                style = StudioType.pageTitle.copy(fontSize = 22.sp),
            )

            // Field 1: What you say
            OutlinedTextField(
                value = sayText,
                onValueChange = { sayText = it },
                label = { Text(stringResource(R.string.dictionary_field_say_label)) },
                placeholder = {
                    Text(
                        stringResource(R.string.dictionary_field_say_placeholder),
                        color = StudioColors.OnSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(
                    onNext = { writeFocusRequester.requestFocus() },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Accent,
                    unfocusedBorderColor = StudioColors.Hairline,
                    focusedLabelColor = StudioColors.Accent,
                    unfocusedLabelColor = StudioColors.OnSurfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            )

            // Field 2: How it should be written
            OutlinedTextField(
                value = writeText,
                onValueChange = { writeText = it },
                label = { Text(stringResource(R.string.dictionary_field_write_label)) },
                placeholder = {
                    Text(
                        stringResource(R.string.dictionary_field_write_placeholder),
                        color = StudioColors.OnSurfaceVariant.copy(alpha = 0.5f),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (isComplete) onSave(trimmedSay, trimmedWrite)
                    },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = StudioColors.Accent,
                    unfocusedBorderColor = StudioColors.Hairline,
                    focusedLabelColor = StudioColors.Accent,
                    unfocusedLabelColor = StudioColors.OnSurfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .focusRequester(writeFocusRequester),
            )

            // Live preview
            if (isComplete) {
                StudioCard(radius = StudioLayout.RadiusField) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "“$trimmedSay” will be written as “$trimmedWrite”",
                            style = StudioType.rowDesc.copy(color = StudioColors.OnBackground),
                        )
                    }
                }
            }

            // Warning for case-insensitive identical text
            if (isIdentical) {
                Text(
                    text = stringResource(R.string.dictionary_warning_identical),
                    style = StudioType.rowDesc.copy(color = StudioColors.ErrorOnBanner),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // Save / Save anyway button
            Button(
                onClick = {
                    if (isComplete) {
                        onSave(trimmedSay, trimmedWrite)
                    }
                },
                enabled = isComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(StudioLayout.RadiusField),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioColors.Accent,
                    contentColor = StudioColors.OnAccent,
                    disabledContainerColor = StudioColors.SurfaceVariant,
                    disabledContentColor = StudioColors.OnSurfaceVariant,
                ),
            ) {
                Text(
                    text = stringResource(
                        if (isIdentical) R.string.dictionary_action_save_anyway else R.string.dictionary_action_save,
                    ),
                    style = StudioType.cta,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
