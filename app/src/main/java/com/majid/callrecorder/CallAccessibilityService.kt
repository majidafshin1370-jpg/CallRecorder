package com.majid.callrecorder

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, empty service. It reads nothing from the screen.
 * On some Android 10+ phones, having an enabled accessibility service makes
 * background microphone access during calls more reliable.
 */
class CallAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
