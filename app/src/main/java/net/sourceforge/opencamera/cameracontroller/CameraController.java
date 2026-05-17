package net.sourceforge.opencamera.cameracontroller;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.graphics.Rect;
import android.hardware.camera2.params.MeteringRectangle;
import android.location.Location;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceHolder;
import android.view.TextureView;

import net.sourceforge.opencamera.MyDebug;

/** CameraController is an abstract class that wraps up the requirements/modern
 *  features of a camera.
 */
public abstract class CameraController {
    private static final String TAG = "CameraController";
    int cameraId;

    public enum TonemapProfile {
        TONEMAPPROFILE_OFF,
        TONEMAPPROFILE_REC709,
        TONEMAPPROFILE_SRGB,
        TONEMAPPROFILE_LOG,
        TONEMAPPROFILE_GAMMA,
        TONEMAPPROFILE_JTVIDEO,
        TONEMAPPROFILE_JTLOG,
        TONEMAPPROFILE_JTLOG2
    }

    public static final String SCENE_MODE_DEFAULT = "auto";
    public static final String COLOR_EFFECT_DEFAULT = "none";
    public static final String WHITE_BALANCE_DEFAULT = "auto";
    public static final String ANTIBANDING_DEFAULT = "auto";
    public static final String EDGE_MODE_DEFAULT = "default";
    public static final String NOISE_REDUCTION_MODE_DEFAULT = "default";
    public static final String ISO_DEFAULT = "auto";
    public static final long EXPOSURE_TIME_DEFAULT = 1000000000L/30;

    public static final int N_IMAGES_NR_DARK = 8;
    public static final int N_IMAGES_NR_DARK_LOW_LIGHT = 15;

    public static int count_camera_parameters_exception;
    public static int count_precapture_timeout;
    public static boolean test_wait_capture_result;
    public static boolean test_release_during_photo;
    public static int test_capture_results;
    public static int test_fake_flash_focus;
    public static int test_fake_flash_precapture;
    public static int test_fake_flash_photo;
    public static int test_af_state_null_focus;
    public static boolean test_used_tonemap_curve;
    public static int test_texture_view_buffer_w;
    public static int test_texture_view_buffer_h;
    public static boolean test_force_run_post_capture;
    public static boolean test_force_slow_preview_start;

    public static class CameraFeaturesCache {
        public final CameraFeatures camera_features;
        // extension info:
        // if we update these fields, remember to update updateExtensionsInfoFromCache()
        public final List<Integer> supported_extensions;
        public final List<Integer> supported_extensions_zoom;
        // maps to map extension id to picture/preview sizes:
        public final Map<Integer, List<Size>> extension_picture_sizes_map;
        public final Map<Integer, List<Size>> extension_preview_sizes_map;

        public CameraFeaturesCache(CameraFeatures camera_features, Map<Integer, List<Size>> extension_picture_sizes_map, Map<Integer, List<Size>> extension_preview_sizes_map) {
            this.camera_features = camera_features;
            this.supported_extensions = camera_features.supported_extensions;
            this.supported_extensions_zoom = camera_features.supported_extensions_zoom;
            this.extension_picture_sizes_map = extension_picture_sizes_map;
            this.extension_preview_sizes_map = extension_preview_sizes_map;
        }
    }

    public static class CameraFeatures {
        public Set<String> physical_camera_ids;
        public boolean is_zoom_supported;
        public int max_zoom;
        public List<Integer> zoom_ratios;
        public boolean supports_face_detection;
        public List<Size> picture_sizes;
        public List<Size> video_sizes;
        public List<Size> video_sizes_high_speed;
        public List<Size> preview_sizes;
        public List<Integer> supported_extensions;
        public List<Integer> supported_extensions_zoom;
        public List<String> supported_flash_values;
        public List<String> supported_focus_values;
        public float [] apertures;
        public int max_num_focus_areas;
        public float minimum_focus_distance;
        public boolean is_exposure_lock_supported;
        public boolean is_white_balance_lock_supported;
        public boolean is_optical_stabilization_supported;
        public boolean is_video_stabilization_supported;
        public boolean is_photo_video_recording_supported;
        public boolean supports_white_balance_temperature;
        public int min_temperature;
        public int max_temperature;
        public boolean supports_iso_range;
        public int min_iso;
        public int max_iso;
        public boolean supports_exposure_time;
        public long min_exposure_time;
        public long max_exposure_time;
        public int min_exposure;
        public int max_exposure;
        public float exposure_step;
        public boolean can_disable_shutter_sound;
        public int tonemap_max_curve_points;
        public boolean supports_tonemap_curve;
        public boolean supports_expo_bracketing;
        public int max_expo_bracketing_n_images;
        public boolean supports_focus_bracketing;
        public boolean supports_burst;
        public boolean supports_jpeg_r;
        public boolean supports_raw;
        public float view_angle_x;
        public float view_angle_y;

        public static boolean supportsFrameRate(List<Size> sizes, int frame_rate) {
            if( sizes == null )
                return false;
            for(Size size : sizes) {
                if( size.supportsFrameRate(frame_rate) ) {
                    return true;
                }
            }
            return false;
        }

        public static Size findSize(List<Size> sizes, Size target, double target_ratio, boolean exact_ratio) {
            Size best_size = null;
            int min_diff = -1;
            for(Size size : sizes) {
                double ratio = (double)size.width / (double)size.height;
                if( exact_ratio && Math.abs(ratio - target_ratio) > 1.0e-5 )
                    continue;
                int diff = Math.abs(size.width - target.width) + Math.abs(size.height - target.height);
                if( best_size == null || diff < min_diff ) {
                    best_size = size;
                    min_diff = diff;
                }
            }
            return best_size;
        }
    }

    public static class RangeSorter implements Comparator<int[]>, Serializable {
        private static final long serialVersionUID = 5802214721073728212L;
        @Override
        public int compare(int[] r1, int[] r2) {
            if (r1[0] == r2[0]) return r1[1] - r2[1];
            return r1[0] - r2[0];
        }
    }

    public static class SizeSorter implements Comparator<Size>, Serializable {
        private static final long serialVersionUID = 5802214721073718212L;
        @Override
        public int compare(Size s1, Size s2) {
            if (s1.width == s2.width) return s1.height - s2.height;
            return s1.width - s2.width;
        }
    }

    public static class Size {
        public final int width;
        public final int height;
        public boolean supports_burst = true;
        public List<Integer> supported_extensions;
        public final List<int[]> fps_ranges; // for video
        public final boolean high_speed; // for video

        public Size(int width, int height, List<int[]> fps_ranges, boolean high_speed) {
            this.width = width;
            this.height = height;
            this.fps_ranges = fps_ranges;
            this.high_speed = high_speed;
        }

        public Size(int width, int height) {
            this(width, height, null, false);
        }

        public boolean supportsRequirements(boolean want_high_speed, boolean want_burst, int want_frame_rate) {
            if( want_high_speed && !this.high_speed )
                return false;
            if( want_burst && !this.supports_burst )
                return false;
            if( want_frame_rate > 0 && !this.supportsFrameRate(want_frame_rate) )
                return false;
            return true;
        }

        public boolean supportsExtension(int extension) {
            return supported_extensions != null && supported_extensions.contains(extension);
        }

        public boolean supportsFrameRate(double frame_rate) {
            if( fps_ranges == null )
                return false;
            for(int [] fps_range : fps_ranges) {
                if( frame_rate >= fps_range[0] && frame_rate <= fps_range[1] )
                    return true;
            }
            return false;
        }

        public int closestFrameRate(double frame_rate) {
            if( fps_ranges == null )
                return -1;
            int closest_fps = -1;
            for(int [] fps_range : fps_ranges) {
                if( frame_rate >= fps_range[0] && frame_rate <= fps_range[1] )
                    return (int)frame_rate;
                if( closest_fps == -1 || Math.abs(fps_range[0] - frame_rate) < Math.abs(closest_fps - frame_rate) )
                    closest_fps = fps_range[0];
                if( Math.abs(fps_range[1] - frame_rate) < Math.abs(closest_fps - frame_rate) )
                    closest_fps = fps_range[1];
            }
            return closest_fps;
        }

        @Override
        public boolean equals(Object o) {
            if( !(o instanceof Size) )
                return false;
            Size that = (Size)o;
            return this.width == that.width && this.height == that.height;
        }

        @Override
        public int hashCode() {
            // hash code based on width and height, should be consistent with equals()
            return width*31 + height;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /** A class for focus or metering areas.
     */
    public static class Area {
        public final Rect rect;
        public final int weight;

        public Area(Rect rect, int weight) {
            this.rect = rect;
            this.weight = weight;
        }
    }

    public static class Face {
        public final int score;
        public final Rect rect;
        public final Rect temp = new Rect(); // for convenience

        public Face(int score, Rect rect) {
            this.score = score;
            this.rect = rect;
        }
    }

    public interface FaceDetectionListener {
        void onFaceDetection(Face[] faces);
    }

    public interface AutoFocusCallback {
        void onAutoFocus(boolean success);
    }

    public interface ContinuousFocusMoveCallback {
        void onContinuousFocusMove(boolean start);
    }

    public interface PictureCallback {
        void onStarted(); // called when actually about to take the photo
        void onCompleted(); // called after all pictures have been taken
        void onPictureTaken(byte[] data);
        /** Only called if BurstType is BURSTTYPE_NORMAL and burst_single_request is true.
         */
        void onBurstPictureTaken(List<byte[]> data);
        void onRawPictureTaken(RawImage raw_image);
        /** Only called if BurstType is BURSTTYPE_NORMAL and burst_single_request is true.
         */
        void onRawBurstPictureTaken(List<RawImage> data);
        void onFrontScreenTurnOn();
        /** Called when a camera extension (Camera2 API only) provides a progress value from 0 to 100.
         */
        void onExtensionProgress(int progress);

        /** Whether the application would currently block if another image is provided.
         *  Used for BURSTTYPE_FOCUS and BURSTTYPE_CONTINUOUS to pause taking further images.
         */
        boolean imageQueueWouldBlock(int n_raw, int n_jpegs);
    }

    public interface ErrorCallback {
        void onError();
    }

    public CameraController(int cameraId) {
        this.cameraId = cameraId;
    }

    public abstract void release();

    public int getCameraId() {
        return cameraId;
    }
    public abstract String getAPI();
    public abstract CameraFeatures getCameraFeatures() throws CameraControllerException;
    public abstract SupportedValues setSceneMode(String value);
    public abstract String getSceneMode();
    public abstract boolean sceneModeAffectsFunctionality();
    public abstract SupportedValues setColorEffect(String value);
    public abstract String getColorEffect();
    public abstract SupportedValues setWhiteBalance(String value);
    public abstract String getWhiteBalance();
    public abstract boolean setWhiteBalanceTemperature(int temperature);
    public abstract int getWhiteBalanceTemperature();
    public abstract SupportedValues setAntiBanding(String value);
    public abstract String getAntiBanding();
    public abstract SupportedValues setEdgeMode(String value);
    public abstract String getEdgeMode();
    public abstract SupportedValues setNoiseReductionMode(String value);
    public abstract String getNoiseReductionMode();
    /**
     * @param value Set to either ISO_DEFAULT, or a string representation of an integer ISO value.
     */
    public abstract SupportedValues setISO(String value);
    public abstract String getISOKey();
    public abstract void setManualISO(boolean manual_iso, int iso);
    public abstract boolean isManualISO();
    public abstract boolean setISO(int iso);
    public abstract int getISO();
    public abstract long getExposureTime();
    public abstract boolean setExposureTime(long exposure_time);
    public abstract void setAperture(float aperture);
    public abstract Size getPictureSize();
    public abstract void setPictureSize(int width, int height);
    public abstract Size getPreviewSize();
    public abstract void setPreviewSize(int width, int height);
    public abstract void setCameraExtension(boolean enabled, int extension);
    public abstract boolean isCameraExtension();
    public abstract int getCameraExtension();

    public enum BurstType {
        BURSTTYPE_NONE,
        BURSTTYPE_EXPO,
        BURSTTYPE_FOCUS,
        BURSTTYPE_NORMAL,
        BURSTTYPE_CONTINUOUS
    }
    public abstract void setBurstType(BurstType burst_type);
    public abstract BurstType getBurstType();
    public abstract void setBurstNImages(int n_images);
    public abstract void setBurstForNoiseReduction(boolean burst_for_noise_reduction, boolean noise_reduction_low_light);
    public abstract boolean isContinuousBurstInProgress();
    public abstract void stopContinuousBurst();
    public abstract void stopFocusBracketingBurst();
    public abstract void setExpoBracketingNImages(int n_images);
    public abstract void setExpoBracketingStops(double stops);
    public abstract void setDummyCaptureHack(boolean dummy_capture_hack);
    public abstract void setUseExpoFastBurst(boolean use_expo_fast_burst);
    public abstract boolean isCaptureFastBurst();
    public abstract boolean isCapturingBurst();
    public abstract int getNBurstTaken();
    public abstract int getBurstTotal();
    public abstract void setJpegR(boolean want_jpeg_r);
    public abstract void setRaw(boolean want_raw, int max_raw_images);
    public abstract void setVideoHighSpeed(boolean want_video_high_speed);
    public abstract void setUseCamera2FakeFlash(boolean use_fake_precapture);
    public abstract boolean getUseCamera2FakeFlash();
    public abstract void setVideoStabilization(boolean enabled);
    public abstract boolean getVideoStabilization();
    public abstract boolean getOpticalStabilization();
    public abstract void setTonemapProfile(TonemapProfile tonemap_profile, float log_profile_strength, float gamma);
    public abstract TonemapProfile getTonemapProfile();
    public abstract int getJpegQuality();
    public abstract void setJpegQuality(int quality);
    public abstract int getZoom();
    public abstract void setZoom(int value);
    public abstract void setZoom(int value, float smooth_zoom);
    public abstract void resetZoom();
    public abstract int getExposureCompensation();
    public abstract boolean setExposureCompensation(int new_exposure);
    public abstract void setPreviewFpsRange(int min, int max);
    public abstract void clearPreviewFpsRange();
    public abstract List<int[]> getSupportedPreviewFpsRange();

    public abstract void setFocusValue(String focus_value);
    public abstract String getFocusValue();
    public abstract float getFocusDistance();
    public abstract boolean setFocusDistance(float focus_distance);
    public abstract void setFocusBracketingNImages(int n_images);
    public abstract void setFocusBracketingAddInfinity(boolean focus_bracketing_add_infinity);
    public abstract void setFocusBracketingSourceDistance(float focus_bracketing_source_distance);
    public abstract float getFocusBracketingSourceDistance();
    public abstract void setFocusBracketingSourceDistanceFromCurrent();
    public abstract void setFocusBracketingTargetDistance(float focus_bracketing_target_distance);
    public abstract float getFocusBracketingTargetDistance();
    public abstract void setFlashValue(String flash_value);
    public abstract String getFlashValue();
    public abstract void setRecordingHint(boolean hint);
    public abstract void setAutoExposureLock(boolean enabled);
    public abstract boolean getAutoExposureLock();
    public abstract void setAutoWhiteBalanceLock(boolean enabled);
    public abstract boolean getAutoWhiteBalanceLock();
    public abstract void setRotation(int rotation);
    public abstract void setLocationInfo(Location location);
    public abstract void removeLocationInfo();
    public abstract void enableShutterSound(boolean enabled);
    public abstract boolean setFocusAndMeteringArea(List<Area> areas);
    public abstract void clearFocusAndMetering();
    public abstract List<Area> getFocusAreas();
    public abstract List<Area> getMeteringAreas();
    public abstract boolean supportsMetering();
    public abstract boolean supportsAutoFocus();
    public abstract boolean focusIsContinuous();
    public abstract boolean focusIsVideo();
    public abstract void reconnect() throws CameraControllerException;
    public abstract void setPreviewDisplay(SurfaceHolder holder) throws CameraControllerException;
    public abstract void setPreviewTexture(TextureView texture) throws CameraControllerException;
    public abstract void updatePreviewTexture();

    public abstract void startPreview(boolean wait_until_started, Runnable runnable, Runnable on_failed) throws CameraControllerException;
    public abstract void stopRepeating();
    public abstract void stopPreview();
    public abstract boolean startFaceDetection();
    public abstract void setFaceDetectionListener(FaceDetectionListener listener);

    public abstract void autoFocus(final AutoFocusCallback cb, boolean capture_follows_autofocus_hint);
    public abstract void setCaptureFollowAutofocusHint(boolean capture_follows_autofocus_hint);
    public abstract void cancelAutoFocus();
    public abstract void setContinuousFocusMoveCallback(ContinuousFocusMoveCallback cb);
    public abstract void takePicture(final PictureCallback picture, final ErrorCallback error);
    public abstract void setDisplayOrientation(int degrees);
    public abstract int getDisplayOrientation();
    public abstract int getCameraOrientation();
    public enum Facing {
        FACING_BACK,
        FACING_FRONT,
        FACING_EXTERNAL,
        FACING_UNKNOWN // returned if the Camera API returned an error or an unknown type
    }
    public abstract Facing getFacing();
    public abstract void unlock();
    public abstract void stopVideoRecorder();
    public abstract void initVideoRecorderPrePrepare(MediaRecorder video_recorder);
    public abstract void initVideoRecorderPostPrepare(MediaRecorder video_recorder, boolean want_photo_video_recording, int video_width, int video_height, int rotation) throws CameraControllerException;
    public abstract String getParametersString();
    public boolean captureResultIsAEScanning() {
        return false;
    }
    public boolean needsFlash() {
        return false;
    }
    public boolean needsFrontScreenFlash() {
        return false;
    }

    public boolean captureResultHasWhiteBalanceTemperature() {
        return false;
    }
    public int captureResultWhiteBalanceTemperature() {
        return 0;
    }
    public boolean captureResultHasIso() {
        return false;
    }
    public int captureResultIso() {
        return 0;
    }
    public boolean captureResultHasExposureTime() {
        return false;
    }
    public long captureResultExposureTime() {
        return 0;
    }
    public boolean captureResultHasFrameDuration() {
        return false;
    }
    public long captureResultFrameDuration() {
        return 0;
    }
    public boolean captureResultHasFocusDistance() {
        return false;
    }
    public float captureResultFocusDistance() {
        return 0.0f;
    }
    public boolean captureResultHasAperture() {
        return false;
    }
    public float captureResultAperture() {
        return 0.0f;
    }
    public abstract boolean shouldCoverPreview();
    public abstract void onError();

    public static class SupportedValues {
        public final List<String> values;
        public final String selected_value;
        SupportedValues(List<String> values, String selected_value) {
            this.values = values;
            this.selected_value = selected_value;
        }
    }

    SupportedValues checkModeIsSupported(List<String> values, String value, String default_value) {
        if( values != null && !values.isEmpty() ) {
            String selected_value = null;
            if( value != null ) {
                for(int i=0;i<values.size();i++) {
                    if( values.get(i).equals(value) ) {
                        selected_value = value;
                        break;
                    }
                }
            }

            if( selected_value == null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "value " + value + " not supported, using default " + default_value);
                for(int i=0;i<values.size();i++) {
                    if( values.get(i).equals(default_value) ) {
                        selected_value = default_value;
                        break;
                    }
                }
            }

            if( selected_value == null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "default value " + default_value + " not supported, using first value " + values.get(0));
                selected_value = values.get(0);
            }

            return new SupportedValues(values, selected_value);
        }
        return null;
    }
}
