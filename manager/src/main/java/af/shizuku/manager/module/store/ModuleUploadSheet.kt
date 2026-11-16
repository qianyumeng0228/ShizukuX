package af.shizuku.manager.module.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R

/**
 * Upload panel for user-contributed modules.
 *
 * The sheet never dead-ends: when no submission endpoint is configured the primary action
 * becomes "export the manifest", and the picked ZIP is still a normal module the user can
 * install locally from the modules page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModuleUploadSheet(
    state: ModuleUploadUiState,
    onPick: () -> Unit,
    onDraftChange: (ModuleDraft) -> Unit,
    onSubmit: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.modules_upload_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.modules_upload_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.candidate?.let { candidate ->
                UploadCandidateCard(candidate)
                if (ModuleUploadClient.ENDPOINT.isBlank()) {
                    Text(
                        text = stringResource(R.string.modules_upload_no_backend),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = stringResource(R.string.modules_upload_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            state.draft?.let { draft ->
                val enabled = !state.submitting
                Text(
                    text = stringResource(R.string.modules_upload_edit_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.modules_upload_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_name),
                    value = draft.name,
                    enabled = enabled,
                    onChange = { onDraftChange(draft.copy(name = it)) }
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_name_zh),
                    value = draft.nameZh,
                    enabled = enabled,
                    onChange = { onDraftChange(draft.copy(nameZh = it)) }
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_author),
                    value = draft.author,
                    enabled = enabled,
                    onChange = { onDraftChange(draft.copy(author = it)) }
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_version),
                    value = draft.version,
                    enabled = enabled,
                    onChange = { onDraftChange(draft.copy(version = it)) }
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_version_code),
                    value = draft.versionCode,
                    enabled = enabled,
                    numeric = true,
                    onChange = { onDraftChange(draft.copy(versionCode = it.trim())) }
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_description),
                    value = draft.description,
                    enabled = enabled,
                    multiline = true,
                    onChange = { onDraftChange(draft.copy(description = it)) }
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_description_zh),
                    value = draft.descriptionZh,
                    enabled = enabled,
                    multiline = true,
                    onChange = { onDraftChange(draft.copy(descriptionZh = it)) }
                )
                DraftField(
                    label = stringResource(R.string.modules_upload_field_tags),
                    value = draft.tags,
                    enabled = enabled,
                    supporting = stringResource(R.string.modules_upload_field_tags_hint),
                    onChange = { onDraftChange(draft.copy(tags = it)) }
                )
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(2.dp))

            if (state.candidate == null) {
                Button(
                    onClick = onPick,
                    enabled = !state.inspecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.inspecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.modules_upload_inspecting))
                    } else {
                        Text(stringResource(R.string.modules_upload_pick))
                    }
                }
            } else {
                if (ModuleUploadClient.ENDPOINT.isBlank()) {
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.modules_upload_export))
                    }
                } else {
                    Button(
                        onClick = onSubmit,
                        enabled = !state.busy && !state.done,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.modules_upload_submitting))
                        } else {
                            Text(stringResource(R.string.modules_upload_submit))
                        }
                    }
                    OutlinedButton(
                        onClick = onPick,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.modules_upload_pick))
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.modules_upload_close))
            }
        }
    }
}

@Composable
private fun DraftField(
    label: String,
    value: String,
    enabled: Boolean,
    multiline: Boolean = false,
    numeric: Boolean = false,
    supporting: String? = null,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        supportingText = supporting?.let { text -> { Text(text) } },
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun UploadCandidateCard(candidate: ModuleUploadCandidate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = candidate.version?.let { "v$it" } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = listOfNotNull(
                    candidate.id,
                    candidate.author,
                    candidate.formattedSize
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${stringResource(R.string.modules_upload_sha)} ${candidate.sha256.take(16)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
