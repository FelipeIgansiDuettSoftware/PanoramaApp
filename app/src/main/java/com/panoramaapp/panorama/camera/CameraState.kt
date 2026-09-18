package com.panoramaapp.panorama.camera

sealed interface CameraState {
    data object Starting : CameraState
    data object PermissionRequired : CameraState
    data object Ready : CameraState
    data class Unavailable(val message: String) : CameraState
    data class Error(val message: String) : CameraState
}
