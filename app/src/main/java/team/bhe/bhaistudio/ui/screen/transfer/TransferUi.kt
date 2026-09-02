package team.bhe.bhaistudio.ui.screen.transfer

import android.content.Context
import android.content.Intent
import team.bhe.bhaistudio.data.transfer.InviteInfo

/** 导入（接收）页状态 */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    /** 正在等待连接，[invite] 用于二维码/地址展示 */
    data class Listening(val invite: InviteInfo) : ImportUiState
    /** 对方已连接，等待用户确认是否接受 */
    data class Asking(val fromDevice: String) : ImportUiState
    data class Transferring(
        val progress: Float,
        val received: Long,
        val total: Long
    ) : ImportUiState
    data object Done : ImportUiState
    data class Error(val message: String) : ImportUiState
}

/** 导出（发送）页状态 */
sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Preparing : ExportUiState
    data class Connecting(val invite: InviteInfo) : ExportUiState
    data class Transferring(
        val progress: Float,
        val sent: Long,
        val total: Long
    ) : ExportUiState
    data class Done(val target: String) : ExportUiState
    data class Error(val message: String) : ExportUiState
}

/** 字节数 → 人类可读（MB / GB） */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 迁移落地需要重启进程：先拉起 Launcher Activity（清空旧任务栈），
 * 再立刻结束当前进程——新进程冷启动时 [team.bhe.bhaistudio.WuJieApplication]
 * 会执行 MigrationApplier.applyIfPending 完成数据覆盖与密钥重写。
 */
fun restartAppForMigration(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    android.os.Process.killProcess(android.os.Process.myPid())
}
