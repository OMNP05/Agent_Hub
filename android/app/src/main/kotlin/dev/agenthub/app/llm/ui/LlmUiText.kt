package dev.agenthub.app.llm.ui

import androidx.annotation.StringRes
import dev.agenthub.app.R
import dev.agenthub.app.llm.data.FieldError

@StringRes
fun FieldError.messageRes(): Int = when (this) {
    FieldError.NameRequired -> R.string.llm_error_name_required
    FieldError.NameTooLong -> R.string.llm_error_name_long
    FieldError.BaseUrlRequired -> R.string.llm_error_url_required
    FieldError.BaseUrlNotHttps -> R.string.llm_error_url_https
    FieldError.BaseUrlInvalid -> R.string.llm_error_url_invalid
    FieldError.BaseUrlHasCredentials -> R.string.llm_error_url_credentials
    FieldError.ModelRequired -> R.string.llm_error_model_required
    FieldError.TemperatureRange -> R.string.llm_error_temperature
    FieldError.MaxTokensRange -> R.string.llm_error_tokens
    FieldError.KeyRequired -> R.string.llm_error_key_required
}
