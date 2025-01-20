object Version {
    const val compose = "1.6.11"
//    const val compose = "1.7.3" // kotlin 2
    const val jvmTarget = "17"
    // https://stackoverflow.com/a/76642065/4398606
    // https://developer.android.com/jetpack/androidx/releases/compose-kotlin#pre-release_kotlin_compatibility
    const val kotlin = "1.9.24"

    object Android {
        const val compileSdk = 35
        const val minSdk = 28
        const val targetSdk = compileSdk
    }
}
