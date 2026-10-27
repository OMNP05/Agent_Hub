package dev.agenthub.app.llm.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.components.SkeletonList
import dev.agenthub.app.core.ui.messageRes
import dev.agenthub.app.llm.data.FieldError
import dev.agenthub.app.llm.data.LlmProfileValidator
import dev.agenthub.app.llm.data.LlmProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmProfileFormScreen(onBack: () -> Unit, viewModel: LlmProfileFormViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    LlmFormEffect.Saved -> {
                        Toast.makeText(context, R.string.llm_saved, Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.llm_new_title else R.string.llm_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> SkeletonList(modifier = Modifier.padding(padding))
            state.notFound -> EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.error_generic),
                message = stringResource(R.string.llm_not_found),
                modifier = Modifier.padding(padding),
            )
            else -> FormBody(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun FormBody(state: LlmFormUiState, vm: LlmProfileFormViewModel, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.llm_provider), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmProvider.entries.forEach { provider ->
                FilterChip(selected = state.provider == provider, onClick = { vm.onProvider(provider) }, label = { Text(provider.label) })
            }
        }

        Field(state, "name", stringResource(R.string.llm_name), state.name, vm::onName)
        Field(
            state, "baseUrl", stringResource(if (state.provider.requiresBaseUrl) R.string.llm_base_url else R.string.llm_base_url_optional),
            state.baseUrl, vm::onBaseUrl, keyboard = KeyboardType.Uri, hint = state.provider.defaultBaseUrl,
        )
        Field(state, "model", stringResource(R.string.llm_model), state.model, vm::onModel, hint = stringResource(R.string.llm_model_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(state, "temperature", stringResource(R.string.llm_temperature), state.temperature, vm::onTemperature, KeyboardType.Decimal, Modifier.weight(1f))
            Field(state, "maxTokens", stringResource(R.string.llm_max_tokens), state.maxTokens, vm::onMaxTokens, KeyboardType.Number, Modifier.weight(1f))
        }

        // Write-only: the field is masked, never prefilled, and the saved key is only ever shown as its last four characters.
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = vm::onApiKey,
            label = { Text(stringResource(if (state.hasStoredKey) R.string.llm_key_replace else R.string.llm_key)) },
            placeholder = state.keyLast4?.takeIf { it.isNotBlank() }?.let { { Text(LlmProfileValidator.maskKey(it)) } },
            supportingText = {
                Text(state.errors["apiKey"]?.let { stringResource(it.messageRes()) } ?: stringResource(R.string.llm_key_note))
            },
            isError = "apiKey" in state.errors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.llm_make_default), Modifier.weight(1f))
            Switch(checked = state.makeDefault, onCheckedChange = vm::onDefault)
        }

        state.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = vm::onTest, enabled = state.testState != TestState.Running && !state.isSaving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.llm_test))
            }
            TestStatus(state.testState)
        }
        Button(onClick = vm::onSave, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.llm_save))
        }
    }
}

@Composable
private fun Field(
    state: LlmFormUiState,
    key: String,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val error: FieldError? = state.errors[key]
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.messageRes())) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        singleLine = true,
        // Callers that share a row pass their own weight; otherwise the field fills the width.
        modifier = if (modifier == Modifier) Modifier.fillMaxWidth() else modifier,
    )
}
