package com.example.localmovielibrary.ui.player

import android.view.Surface
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import com.example.localmovielibrary.playback.vr.VrControlMode
import com.example.localmovielibrary.playback.vr.VrMode

@OptIn(UnstableApi::class)
@Composable
fun VrSphericalPlayerView(
    player: ExoPlayer,
    vrMode: VrMode,
    controlMode: VrControlMode,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SphericalGLSurfaceView(context).apply {
                setDefaultStereoMode(vrMode.stereoMode)
                setUseSensorRotation(controlMode.useSensor)
                setOnClickListener { onTap() }
                player.setVideoFrameMetadataListener(getVideoFrameMetadataListener())
                player.setCameraMotionListener(getCameraMotionListener())
                addVideoSurfaceListener(
                    object : SphericalGLSurfaceView.VideoSurfaceListener {
                        override fun onVideoSurfaceCreated(surface: Surface) {
                            player.setVideoSurface(surface)
                        }

                        override fun onVideoSurfaceDestroyed(surface: Surface) {
                            player.clearVideoSurface(surface)
                            player.clearVideoFrameMetadataListener(getVideoFrameMetadataListener())
                            player.clearCameraMotionListener(getCameraMotionListener())
                        }
                    }
                )
                onResume()
            }
        },
        update = { view ->
            view.setDefaultStereoMode(vrMode.stereoMode)
            view.setUseSensorRotation(controlMode.useSensor)
            view.setOnClickListener { onTap() }
            player.setVideoFrameMetadataListener(view.getVideoFrameMetadataListener())
            player.setCameraMotionListener(view.getCameraMotionListener())
            player.setVideoSurface(view.getVideoSurface())
        },
        onRelease = { view ->
            view.onPause()
            player.clearVideoSurface(view.getVideoSurface())
            player.clearVideoFrameMetadataListener(view.getVideoFrameMetadataListener())
            player.clearCameraMotionListener(view.getCameraMotionListener())
        }
    )
}
