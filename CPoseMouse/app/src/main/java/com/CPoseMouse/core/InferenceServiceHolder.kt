package com.CPoseMouse.core

object InferenceServiceHolder {
    @Volatile var loop: InferenceLoop? = null
    @Volatile var adaptivePidController: AdaptivePidController? = null

    @Volatile var lts: LowTextureSuppressor? = null
    @Volatile var capture: ScreenCapture? = null
}
