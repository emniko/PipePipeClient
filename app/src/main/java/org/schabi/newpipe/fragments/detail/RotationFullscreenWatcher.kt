package org.schabi.newpipe.fragments.detail

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.schabi.newpipe.R
import org.schabi.newpipe.player.Player
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.util.DeviceUtils

/**
 * YouTube-style "turn the phone to watch fullscreen".
 *
 * With system auto-rotate on, the configuration change already switches the player to
 * fullscreen (see PlayerUiModeController.onOrientationChanged). With auto-rotate off nothing
 * rotates, so this listens to the orientation sensor instead: turning the phone sideways while a
 * horizontal video is open enters fullscreen, turning it back upright leaves the fullscreen that
 * was entered this way.
 */
class RotationFullscreenWatcher(
    private val context: Context,
    private val player: () -> Player?,
    private val sheetState: () -> Int,
) : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {

    private var lastLandscape: Boolean? = null
    private var enteredBySensor = false

    fun start() {
        lastLandscape = null
        if (canDetectOrientation()) {
            enable()
        }
    }

    fun stop() {
        disable()
    }

    override fun onOrientationChanged(degrees: Int) {
        if (degrees == ORIENTATION_UNKNOWN) {
            return
        }
        if (!PlayerHelper.globalScreenOrientationLocked(context) || !enabledInSettings()) {
            // auto-rotate is on: the normal configuration change handles fullscreen
            enteredBySensor = false
            return
        }
        // only act on clear postures, so holding the phone at an angle does not flicker
        val landscape = when (degrees) {
            in LANDSCAPE_LEFT, in LANDSCAPE_RIGHT -> true
            in 0..PORTRAIT_TOLERANCE, in (360 - PORTRAIT_TOLERANCE)..359 -> false
            else -> return
        }
        if (landscape == lastLandscape) {
            return
        }
        lastLandscape = landscape

        val player = player() ?: return
        if (!player.videoPlayerSelected() || DeviceUtils.isTv(context)
            || DeviceUtils.isTablet(context)) {
            return
        }
        if (landscape) {
            if (!player.isFullscreen && !player.isVerticalVideo
                && sheetState() == BottomSheetBehavior.STATE_EXPANDED) {
                player.changeFullscreen(true)
                enteredBySensor = true
            }
        } else if (enteredBySensor) {
            enteredBySensor = false
            if (player.isFullscreen) {
                player.changeFullscreen(false)
            }
        }
    }

    private fun enabledInSettings(): Boolean = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getBoolean(context.getString(R.string.rotation_controls_fullscreen_key),
            !DeviceUtils.isTablet(context))

    companion object {
        private val LANDSCAPE_LEFT = 60..120
        private val LANDSCAPE_RIGHT = 240..300
        private const val PORTRAIT_TOLERANCE = 25
    }
}
