package com.etheroal.os.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.etheroal.os.core.agent.ScreenElement
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** What is on screen right now, with the live nodes kept beside their numbered descriptions. */
class Screen(val packageName: String, val elements: List<ScreenElement>, private val nodes: List<AccessibilityNodeInfo>) {
    fun node(index: Int): AccessibilityNodeInfo? = nodes.getOrNull(index - 1)
    fun element(index: Int): ScreenElement? = elements.getOrNull(index - 1)
}

/**
 * The hands of the agent. Off unless the user turns it on in Accessibility settings.
 *
 * It does nothing by itself: it does not listen to what happens on the phone (events are ignored)
 * and it stores nothing. It reads the screen and acts only when an operator agent asks, which
 * happens only during a task the user started, and every step has already passed its checks.
 */
class EtheroalAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit   // deliberately not observed

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        hideBanner()
        return super.onUnbind(intent)
    }

    fun snapshot(): Screen {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return Screen("", emptyList(), emptyList())
        val elements = ArrayList<ScreenElement>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (elements.size >= MAX_ELEMENTS || depth > 40) return
            if (node.isVisibleToUser) {
                val password = node.isPassword
                val label = if (password) "" else listOf(node.text, node.contentDescription, node.hintText)
                    .firstOrNull { !it.isNullOrBlank() }?.toString()?.replace('\n', ' ')?.trim()?.take(80).orEmpty()
                if (node.isClickable || node.isEditable || node.isScrollable || label.isNotEmpty()) {
                    nodes += node
                    elements += ScreenElement(
                        index = nodes.size,
                        kind = node.className?.toString()?.substringAfterLast('.').orEmpty().ifEmpty { "View" },
                        label = label,
                        clickable = node.isClickable || clickableParent(node) != null,
                        editable = node.isEditable,
                        password = password,
                        scrollable = node.isScrollable,
                    )
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return Screen(root.packageName?.toString().orEmpty(), elements, nodes)
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = node.parent
        repeat(6) {
            val current = p ?: return null
            if (current.isClickable) return current
            p = current.parent
        }
        return null
    }

    fun tap(node: AccessibilityNodeInfo): Boolean {
        val target = if (node.isClickable) node else clickableParent(node)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // Some custom views only react to a real touch.
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(gesture, null, null)
    }

    fun type(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.isPassword || !node.isEditable) return false           // checked again at the last moment
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(screen: Screen, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return screen.elements.filter { it.scrollable }.any { screen.node(it.index)?.performAction(action) == true }
    }

    /** Puts a vault secret straight into a password field. The value never passes through a model. */
    fun fillSecret(node: AccessibilityNodeInfo, secret: CharSequence): Boolean {
        if (!node.isEditable) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, secret) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * A screenshot for the operator, scaled down to keep the request small. Android 11+ only; on
     * older phones the operator works from the element list alone. Etheroal's banner is hidden for
     * the capture so it never covers the app.
     */
    suspend fun screenshot(maxSide: Int = 896): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        main.post { overlay?.visibility = View.INVISIBLE }
        delay(60)
        val shot = suspendCancellableCoroutine<Bitmap?> { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        soft?.let {
                            val scale = maxSide.toFloat() / maxOf(it.width, it.height)
                            if (scale >= 1f) it else Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                        }
                    }.getOrNull()
                    if (cont.isActive) cont.resume(bmp)
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        }
        main.post { overlay?.visibility = View.VISIBLE }
        return shot
    }

    /**
     * A banner over whatever app is in front while a task runs, so the user always sees that
     * Etheroal is acting and can stop it. With [question] set it asks before a sensitive step.
     */
    fun showBanner(status: String, question: String?, onStop: () -> Unit, onAnswer: (Boolean) -> Unit) = main.post {
        hideBannerNow()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(Color.parseColor("#F00E1018"))
                setStroke(px(1), Color.parseColor("#40FFFFFF"))
            }
        }
        box.addView(TextView(this).apply {
            text = question ?: status
            setTextColor(Color.parseColor("#F1F2F6"))
            textSize = 14f
            maxLines = 3
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor("#93A0FF"))
            setBackgroundColor(Color.TRANSPARENT)
            minHeight = px(48)
            setOnClickListener { onClick() }
        }
        if (question != null) {
            row.addView(button("Skip") { onAnswer(false) })
            row.addView(button("Allow") { onAnswer(true) })
        }
        row.addView(button("Stop") { onStop() })
        box.addView(row)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = px(40)
            horizontalMargin = 0.03f
        }
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, params)
            overlay = box
        }
    }

    fun hideBanner() = main.post { hideBannerNow() }

    private fun hideBannerNow() {
        overlay?.let { runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
    }

    companion object {
        private const val MAX_ELEMENTS = 70

        /** The connected service, or null while the user has it switched off. */
        @Volatile var instance: EtheroalAccessibilityService? = null
            private set
    }
}
