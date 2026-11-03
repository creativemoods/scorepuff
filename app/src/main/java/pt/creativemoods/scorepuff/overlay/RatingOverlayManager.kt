// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import pt.creativemoods.scorepuff.data.DiagnosticsRepository
import pt.creativemoods.scorepuff.data.models.ScreenRect

class RatingOverlayManager(private val service: AccessibilityService) {
    private var windows: WindowManager? = null
    private var view: RatingBadgeView? = null
    private val attachment = OverlayAttachment(android.os.SystemClock::uptimeMillis) {
        DiagnosticsRepository.error("NROverlay", it)
    }
    var badgeCount = 0
        private set
    var onBadgeCount: ((Int) -> Unit)? = null

    fun show(titles: List<TitleBadge>, viewport: ScreenRect? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (titles.isEmpty()) { hide(); return }
        if (attachment.attached) { view?.submit(titles, viewport); return }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        attachment.show {
            // Use a fresh accessibility window context/token for each attachment.
            val context = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val display = service.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            } else service
            val manager = context.getSystemService(WindowManager::class.java)
            val nextView = RatingBadgeView(context).also { badgeView ->
                badgeView.onBadgeCount = { count ->
                    if (attachment.attached) { badgeCount = count; onBadgeCount?.invoke(count) }
                }
                badgeView.submit(titles, viewport)
            }
            try { manager.addView(nextView, params) }
            catch (failure: RuntimeException) {
                // addView may leave a partially registered ViewRoot after a rejected token.
                runCatching { manager.removeViewImmediate(nextView) }
                throw failure
            }
            windows = manager
            view = nextView
            nextView.requestApplyInsets()
        }
    }

    fun hide() {
        check(Looper.myLooper() == Looper.getMainLooper())
        attachment.hide { view?.let { windows?.removeViewImmediate(it) } }
        badgeCount = 0
        view?.submit(emptyList())
        view = null
        windows = null
    }
}
