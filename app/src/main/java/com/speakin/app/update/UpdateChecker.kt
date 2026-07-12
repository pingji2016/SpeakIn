package com.speakin.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class UpdateStatus {
    IDLE,
    CHECKING,
    NO_UPDATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    PLAY_SERVICES_UNAVAILABLE
}

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val availableVersionCode: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytesToDownload: Long = 0,
    val errorMessage: String? = null
) {
    val downloadProgress: Float
        get() = if (totalBytesToDownload > 0) {
            bytesDownloaded.toFloat() / totalBytesToDownload.toFloat()
        } else 0f

    val downloadPercent: Int
        get() = (downloadProgress * 100).toInt()
}

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val appUpdateManager = AppUpdateManagerFactory.create(context)

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private var installStateListener: InstallStateUpdatedListener? = null

    val requestCode = 5001

    /**
     * 检查是否有可用更新。使用 KTX 扩展的 suspend 函数。
     */
    suspend fun checkForUpdate() {
        _updateState.value = UpdateUiState(status = UpdateStatus.CHECKING)
        try {
            val info = appUpdateManager.requestAppUpdateInfo()
            when {
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                    _updateState.value = UpdateUiState(
                        status = UpdateStatus.UPDATE_AVAILABLE,
                        availableVersionCode = info.availableVersionCode()
                    )
                }
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    // 之前的更新还在下载中，继续监听进度
                    _updateState.value = UpdateUiState(status = UpdateStatus.DOWNLOADING)
                    startProgressMonitoring()
                }
                else -> {
                    _updateState.value = UpdateUiState(status = UpdateStatus.NO_UPDATE)
                }
            }
        } catch (e: Exception) {
            _updateState.value = UpdateUiState(
                status = UpdateStatus.FAILED,
                errorMessage = e.message
            )
        }
    }

    /**
     * 启动 Flexible 更新流程（后台下载，不打断用户）。
     * 需要 Activity 引用以启动 Play 更新 UI。
     */
    fun startFlexibleUpdate(activity: Activity) {
        if (_updateState.value.status != UpdateStatus.UPDATE_AVAILABLE) return

        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            ) {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    AppUpdateType.FLEXIBLE,
                    activity,
                    requestCode
                )
            }
        }.addOnFailureListener { e ->
            _updateState.value = UpdateUiState(
                status = UpdateStatus.FAILED,
                errorMessage = e.message
            )
        }
    }

    /** 用户接受了更新，开始监听下载进度 */
    fun onUpdateAccepted() {
        _updateState.value = UpdateUiState(status = UpdateStatus.DOWNLOADING)
        startProgressMonitoring()
    }

    /** 用户取消了更新 */
    fun onUpdateDeclined() {
        _updateState.value = UpdateUiState(
            status = UpdateStatus.NO_UPDATE,
            errorMessage = null
        )
    }

    /** 更新流程失败 */
    fun onUpdateFailed() {
        _updateState.value = UpdateUiState(
            status = UpdateStatus.FAILED,
            errorMessage = "Update flow failed"
        )
    }

    /** 下载完成，调用此方法完成安装并重启应用 */
    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    /**
     * 兜底方案：直接跳转到 Google Play 商店页面。
     * 适用于没有 Play Services 或更新 API 不可用的情况。
     */
    fun openInPlayStore(activity: Activity) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=${context.packageName}")
                setPackage("com.android.vending")
            }
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Play Store 未安装，打开网页版
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    // ── 内部方法 ──

    private fun startProgressMonitoring() {
        stopProgressMonitoring()
        installStateListener = InstallStateUpdatedListener { state ->
            when (state.installStatus()) {
                InstallStatus.DOWNLOADING -> {
                    _updateState.update {
                        it.copy(
                            status = UpdateStatus.DOWNLOADING,
                            bytesDownloaded = state.bytesDownloaded(),
                            totalBytesToDownload = state.totalBytesToDownload()
                        )
                    }
                }
                InstallStatus.DOWNLOADED -> {
                    stopProgressMonitoring()
                    _updateState.value = UpdateUiState(status = UpdateStatus.DOWNLOADED)
                }
                InstallStatus.FAILED,
                InstallStatus.CANCELED -> {
                    stopProgressMonitoring()
                    _updateState.value = UpdateUiState(
                        status = UpdateStatus.FAILED,
                        errorMessage = "Download failed or cancelled"
                    )
                }
                else -> { /* PENDING, INSTALLING, INSTALLED */ }
            }
        }
        appUpdateManager.registerListener(installStateListener!!)
    }

    private fun stopProgressMonitoring() {
        installStateListener?.let { appUpdateManager.unregisterListener(it) }
        installStateListener = null
    }
}
