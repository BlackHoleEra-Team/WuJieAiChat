package team.bhe.bhaistudio.ui.screen.transfer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository
import team.bhe.bhaistudio.data.repository.SearchConfigRepository
import team.bhe.bhaistudio.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.first
import team.bhe.bhaistudio.data.transfer.InviteInfo
import team.bhe.bhaistudio.data.transfer.TransferCrypto
import team.bhe.bhaistudio.data.transfer.TransferReceiver
import team.bhe.bhaistudio.data.transfer.TransferSender
import team.bhe.bhaistudio.data.transfer.buildMigrationPayload
import team.bhe.bhaistudio.data.transfer.lanIPv4
import team.bhe.bhaistudio.data.transfer.localDeviceName
import team.bhe.bhaistudio.data.transfer.parseInvite

// ─────────────────────────────────────────────────────────
// 导入（接收方 / 新机）
// ─────────────────────────────────────────────────────────

class ImportViewModel(
    context: Application
) : AndroidViewModel(context) {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var receiver: TransferReceiver? = null
    private var acceptContinuation: kotlin.coroutines.Continuation<Boolean>? = null
    private var receiveJob: Job? = null

    /** 进入接收模式：监听随机端口，展示二维码等待对方连接 */
    fun startListening() {
        if (receiver != null) return
        val app = getApplication<Application>()
        val ip = lanIPv4()
            ?: run {
                _state.value = ImportUiState.Error("无法获取本机局域网地址，请确认已连接 WiFi")
                return
            }
        val token = TransferCrypto.randomHex(16)

        val recv = TransferReceiver(
            context = app,
            token = token,
            onIncomingRequest = { from -> askUser(from) },
            onProgress = { received, total ->
                _state.value = ImportUiState.Transferring(
                    progress = if (total > 0) received.toFloat() / total else 0f,
                    received = received,
                    total = total
                )
            }
        )
        receiver = recv
        recv.start()
        val invite = InviteInfo(ip, recv.port, token)
        _state.value = ImportUiState.Listening(invite)

        receiveJob = viewModelScope.launch {
            val result = recv.acceptAndReceive()
            recv.stop()
            receiver = null
            result.fold(
                onSuccess = {
                    // 覆盖对端 UI 正在传输的中间状态
                    if (_state.value !is ImportUiState.Error) {
                        _state.value = ImportUiState.Done
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) return@launch
                    _state.value = ImportUiState.Error(e.message ?: "接收失败")
                }
            )
        }
    }

    /** 挂起等待 UI 上的接受/拒绝决定 */
    private suspend fun askUser(from: String): Boolean =
        suspendCoroutine { cont ->
            acceptContinuation = cont
            _state.value = ImportUiState.Asking(from)
        }

    /** 用户在确认框上点击了接受 / 拒绝 */
    fun respond(accept: Boolean) {
        val cont = acceptContinuation ?: return
        acceptContinuation = null
        runCatching { cont.resume(accept) }
    }

    fun cancel() {
        receiveJob?.cancel()
        // 若正在等待用户确认，解除挂起（回 false，对端会收到拒绝并断开）
        acceptContinuation?.let { runCatching { it.resume(false) } }
        acceptContinuation = null
        runCatching { receiver?.stop() }
        receiver = null
        _state.value = ImportUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { receiver?.stop() }
    }
}

// ─────────────────────────────────────────────────────────
// 导出（发送方 / 旧机）
// ─────────────────────────────────────────────────────────

class ExportViewModel(
    context: Application,
    private val providerRepository: ProviderConfigRepository,
    private val searchRepository: SearchConfigRepository,
    private val userProfileRepository: UserProfileRepository
) : AndroidViewModel(context) {

    private val _state = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var sendJob: Job? = null

    /** 解析二维码内容并开始连接发送 */
    fun connectWithRaw(raw: String) {
        val invite = parseInvite(raw)
        if (invite == null) {
            _state.value = ExportUiState.Error("二维码内容无效，请扫描接收方页面的二维码")
        } else {
            connect(invite)
        }
    }

    /** 手动输入地址连接（无扫码环境 / 调试用） */
    fun connectManual(host: String, portText: String, tokenText: String) {
        val port = portText.trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            _state.value = ExportUiState.Error("地址格式不正确")
            return
        }
        val token = tokenText.trim().ifBlank { "0".repeat(32) }
        connect(InviteInfo(host.trim(), port, token))
    }

    private fun connect(invite: InviteInfo) {
        if (sendJob?.isActive == true) return
        sendJob = viewModelScope.launch {
            val app = getApplication<Application>()
            _state.value = ExportUiState.Preparing
            val payload = runCatching {
                buildMigrationPayload(
                    context = app,
                    providerRepository = providerRepository,
                    searchRepository = searchRepository,
                    deviceName = localDeviceName(),
                    profileNickname = userProfileRepository.nickname.first(),
                    profileAvatarUri = userProfileRepository.avatarUri.first()
                )
            }.getOrElse { e ->
                _state.value = ExportUiState.Error(e.message ?: "准备数据失败")
                return@launch
            }

            _state.value = ExportUiState.Connecting(invite)
            val sender = TransferSender(
                host = invite.host,
                port = invite.port,
                token = invite.token,
                deviceName = localDeviceName(),
                payload = payload,
                onProgress = { sent, total ->
                    _state.value = ExportUiState.Transferring(
                        progress = if (total > 0) sent.toFloat() / total else 0f,
                        sent = sent,
                        total = total
                    )
                }
            )
            val result = sender.run()
            result.fold(
                onSuccess = { _state.value = ExportUiState.Done(invite.host) },
                onFailure = { e ->
                    if (e is CancellationException) return@launch
                    _state.value = ExportUiState.Error(e.message ?: "发送失败")
                }
            )
        }
    }

    fun backToIdle() {
        sendJob?.cancel()
        _state.value = ExportUiState.Idle
    }
}
