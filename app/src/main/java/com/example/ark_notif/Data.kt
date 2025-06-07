package com.example.ark_notif

// Update APK
data class AppUpdateResponse(
    val version: Int,
    val artifactType: ArtifactType,
    val applicationId: String,
    val variantName: String,
    val elements: List<Element>,
    val elementType: String,
    val minSdkVersionForDexing: Int
)
data class ArtifactType(
    val type: String,
    val kind: String
)
data class Element(
    val type: String,
    val filters: List<Any>,
    val attributes: List<Any>,
    val versionCode: Int,
    val versionName: String,
    val outputFile: String
)
// Update APK end

data class NotificationStatusResponse(
    val success: Boolean,
    val shouldRing: Boolean?,
    val error: String?
)