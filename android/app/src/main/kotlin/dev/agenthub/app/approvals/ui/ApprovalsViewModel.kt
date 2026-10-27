package dev.agenthub.app.approvals.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agenthub.app.approvals.data.ApprovalRepository
import dev.agenthub.app.approvals.data.Decision
import dev.agenthub.app.core.error.AppResult
import dev.agenthub.app.core.error.DataError
import dev.agenthub.app.core.model.Approval
import dev.agenthub.app.core.model.ApprovalStatus
import dev.agenthub.app.core.model.RiskLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApprovalsUiState(
    val approvals: List<Approval> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: DataError? = null,
    /** Approvals whose decision is being sent. Their buttons stay disabled so a second tap cannot send another. */
    val inFlight: Set<String> = emptySet(),
    /** Approval waiting for the extra confirmation step (Dangerous actions). */
    val confirming: Approval? = null,
    val highlightId: String? = null,
) {
    val pending: List<Approval> get() = approvals.filter { it.status == ApprovalStatus.Pending }
    val showEmpty: Boolean get() = !isLoading && error == null && pending.isEmpty()
    val showBlockingError: Boolean get() = !isLoading && error != null && approvals.isEmpty()
}

sealed interface ApprovalsEffect {
    data class Decided(val decision: Decision) : ApprovalsEffect
    data object AlreadyHandled : ApprovalsEffect
}

@HiltViewModel
class ApprovalsViewModel @Inject constructor(
    private val repository: ApprovalRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApprovalsUiState())
    val uiState: StateFlow<ApprovalsUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ApprovalsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init { refresh() }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.pending()) {
                is AppResult.Success -> _uiState.update { it.copy(approvals = result.value, isLoading = false, isRefreshing = false) }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = result.error) }
            }
        }
    }

    /** Opened from a notification: scroll to and mark this approval, refreshing so it is in the list. */
    fun highlight(approvalId: String?) {
        _uiState.update { it.copy(highlightId = approvalId) }
        if (approvalId != null && _uiState.value.approvals.none { it.id == approvalId }) refresh()
    }

    /** Tap on Approve. Write actions go straight through; Dangerous ones ask once more on a separate step. */
    fun onApproveTap(approval: Approval) {
        if (approval.id in _uiState.value.inFlight || approval.status != ApprovalStatus.Pending) return
        if (ToolInfo.risk(approval.tool) == RiskLevel.Dangerous) {
            _uiState.update { it.copy(confirming = approval) }
        } else {
            submit(approval, Decision.Approve)
        }
    }

    fun onConfirmApprove() {
        val approval = _uiState.value.confirming ?: return
        _uiState.update { it.copy(confirming = null) }
        submit(approval, Decision.Approve)
    }

    fun onConfirmDismiss() = _uiState.update { it.copy(confirming = null) }

    fun onRejectTap(approval: Approval) {
        if (approval.id in _uiState.value.inFlight || approval.status != ApprovalStatus.Pending) return
        submit(approval, Decision.Reject)
    }

    private fun submit(approval: Approval, decision: Decision) {
        // Claimed synchronously so a double tap in the same frame cannot start two requests.
        var claimed = false
        _uiState.update { state ->
            if (approval.id in state.inFlight) state else { claimed = true; state.copy(inFlight = state.inFlight + approval.id) }
        }
        if (!claimed) return
        viewModelScope.launch {
            when (val result = repository.decide(approval.id, decision)) {
                is AppResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            approvals = state.approvals.map { if (it.id == approval.id) result.value else it },
                            inFlight = state.inFlight - approval.id,
                        )
                    }
                    _effects.trySend(ApprovalsEffect.Decided(decision))
                }
                is AppResult.Failure -> {
                    val gone = result.error == DataError.ApprovalExpired
                    _uiState.update { state ->
                        state.copy(
                            inFlight = state.inFlight - approval.id,
                            // A decided or expired approval can never be approved again, so drop it from the list.
                            approvals = if (gone) state.approvals.filterNot { it.id == approval.id } else state.approvals,
                            error = if (gone) state.error else result.error,
                        )
                    }
                    if (gone) _effects.trySend(ApprovalsEffect.AlreadyHandled)
                }
            }
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }
}
