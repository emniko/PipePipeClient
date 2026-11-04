package org.schabi.newpipe.fragments.detail

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.view.OrientationEventListener
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.schabi.newpipe.R
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.PlayerUiModeController
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.DeviceUtils

/**
 * YouTube-style fullscreen and rotation on phones, driven by how the phone is actually held:
 *
 * - The fullscreen button locks the screen to landscape (PlayerUiModeController). Once the phone
 *   is really turned sideways, turning it upright again leaves fullscreen.
 * - Leaving fullscreen locks the screen upright; the lock is released as soon as the phone is
 *   upright, so auto-rotate works normally again afterwards.
 * - With system auto-rotate off, turning the phone sideways while a horizontal video is open
 *   enters fullscreen anyway. With auto-rotate on, the configuration change already does that
 *   (PlayerUiModeController.onOrientationChanged).
 */
class RotationFullscreenWatcher(
    private val activity: Activity,
    private val player: () -> Player?,
    private val sheetState: () -> Int,
) : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {

    private var lastLandscape: Boolean? = null

    /** The phone was held sideways while in fullscreen, so holding it upright means "leave". */
    private var heldSidewaysInFullscreen = false

    fun start() {
        lastLandscape = null
        heldSidewaysInFullscreen = false
        if (canDetectOrientation()) {
            enable()
        }
    }

    fun stop() {
        disable()
        // never leave the app stuck upright when the page goes away
        if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            PlayerUiModeController.setOrientation(activity,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        }
    }

    override fun onOrientationChanged(degrees: Int) {
        if (degrees == ORIENTATION_UNKNOWN) {
            return
        }
        // only act on clear postures, so holding the phone at an angle does not flicker
        val landscape = when (degrees) {
            in LANDSCAPE_LEFT, in LANDSCAPE_RIGHT -> true
            in 0..PORTRAIT_TOLERANCE, in (360 - PORTRAIT_TOLERANCE)..359 -> false
            else -> return
        }
        val turned = landscape != lastLandscape
        lastLandscape = landscape

        val player = player() ?: return
        if (!player.videoPlayerSelected() || DeviceUtils.isTv(activity)
            || DeviceUtils.isTablet(activity)) {
            return
        }
        val requested = activity.requestedOrientation

        if (player.isFullscreen) {
            if (player.isVerticalVideo) {
                return
            }
            if (landscape) {
                heldSidewaysInFullscreen = true
                if (autoRotateFullscreen()
                    && requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                    // hand rotation back to the system: turning upright then leaves
                    // fullscreen through the normal configuration change
                    PlayerUiModeController.setOrientation(activity,
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                }
            } else if (heldSidewaysInFullscreen) {
                heldSidewaysInFullscreen = false
                player.changeFullscreen(false)
            }
            return
        }

        heldSidewaysInFullscreen = false
        if (!landscape && requested == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            // upright again after leaving fullscreen: release the lock
            PlayerUiModeController.setOrientation(activity,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            return
        }
        if (landscape && turned && requested != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            && PlayerHelper.globalScreenOrientationLocked(activity) && enabledInSettings()
            && !player.isVerticalVideo
            && sheetState() == BottomSheetBehavior.STATE_EXPANDED) {
            // auto-rotate is off, so nothing else reacts to the phone being turned
            player.changeFullscreen(true)
        }
    }

    /** Whether the system rotation drives fullscreen (auto-rotate on and the setting on). */
    private fun autoRotateFullscreen(): Boolean =
        !PlayerHelper.globalScreenOrientationLocked(activity) && enabledInSettings()

    private fun enabledInSettings(): Boolean = PreferenceManager
        .getDefaultSharedPreferences(activity)
        .getBoolean(activity.getString(R.string.rotation_controls_fullscreen_key),
            !DeviceUtils.isTablet(activity))

    companion object {
        private val LANDSCAPE_LEFT = 60..120
        private val LANDSCAPE_RIGHT = 240..300
        private const val PORTRAIT_TOLERANCE = 25
    }
}
