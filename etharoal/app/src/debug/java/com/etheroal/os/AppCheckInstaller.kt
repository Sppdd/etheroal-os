package com.etheroal.os

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds only: the debug provider prints a token in Logcat (tag DebugAppCheckProvider)
 * that must be registered in Firebase console > App Check > Apps > Manage debug tokens.
 * This class and the debug dependency do not exist in release builds.
 */
object AppCheckInstaller {
    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
