package com.PMouse.core

import android.graphics.Bitmap

data class Keypoint(val x: Float, val y: Float, val conf: Float)
data class PoseResult(val confidence: Float, val keypoints: List<Keypoint>)

interface InferenceEngine {
    val name: String
    fun loadModel(context: android.content.Context): Boolean
    fun detect(bitmap: Bitmap, confThresh: Float): List<PoseResult>
    fun isInferenceFast(): Boolean
    fun lastInferMs(): Double
    fun release()
}