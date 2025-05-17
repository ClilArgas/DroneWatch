package com.dji.sdk.sample.demo.dronewatch;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.TextureView;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.utils.ModuleVerificationUtil;
import com.dji.sdk.sample.internal.utils.ToastUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DroneWatchActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "DroneWatchActivity";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // Firestore REST endpoint
    private static final String FIRESTORE_BASE_URL =
        "https://firestore.googleapis.com/v1/projects/aeroaid-39329/databases/(default)/documents";

    // UI
    private TextureView mVideoSurface;
    private TextView mLocationInfoTv;
    private View mFloatingButton;  // Round floating button

    // DJI
    private DJICodecManager mCodecManager;
    private Camera mCamera;
    private FlightController mFlightController;

    // Auth & assignment
    private String mAuthToken;
    private String mUserId;
    private String mEmergencyId;
    private String mAssignmentId;

    // Networking
    private OkHttpClient mOkHttpClient;
    private Handler mHandler;
    private Timer mLocationTimer;

    // Video listener
    private VideoFeeder.VideoDataListener mVideoDataListener = new VideoFeeder.VideoDataListener() {
        @Override
        public void onReceive(byte[] videoBuffer, int size) {
            if (mCodecManager != null) {
                mCodecManager.sendDataToDecoder(videoBuffer, size);
            }
        }
    };

    // SharedPrefs keys
    private static final String PREF_FILE         = "aeroaid_prefs";
    private static final String PREF_TOKEN        = "auth_token";
    private static final String PREF_USER_ID      = "user_id";
    private static final String PREF_EMERGENCY_ID = "emergencyId";
    private static final String PREF_ASSIGN_ID    = "currentAssignmentId";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drone_watch);

        // Load prefs
        android.content.SharedPreferences prefs =
            getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        mAuthToken    = prefs.getString(PREF_TOKEN, null);
        mUserId       = prefs.getString(PREF_USER_ID, null);
        mEmergencyId  = prefs.getString(PREF_EMERGENCY_ID, "");
        mAssignmentId = prefs.getString(PREF_ASSIGN_ID, "");

        if (mAuthToken == null || mUserId == null ||
            mEmergencyId.isEmpty() || mAssignmentId.isEmpty()) {
            Toast.makeText(this, "Authentication/assignment missing", Toast.LENGTH_LONG).show();
            startActivity(new android.content.Intent(this, LoginActivity.class));
            finish();
            return;
        }

        mOkHttpClient = new OkHttpClient();
        mHandler      = new Handler();

        initUI();
        initSDKComponents();
        startLocationUpdates();
        addFloatingButton(); // Add the small round button
    }

    private void initUI() {
        mVideoSurface   = findViewById(R.id.video_surface);
        mLocationInfoTv = findViewById(R.id.tv_location_info);

        if (mVideoSurface != null) {
            mVideoSurface.setSurfaceTextureListener(this);
        }
    }

    // Add a small, round, semi-transparent button in the bottom-right corner
    private void addFloatingButton() {
        View floatingButton = new View(this);
        floatingButton.setBackground(getResources().getDrawable(android.R.drawable.ic_menu_camera));
        floatingButton.setAlpha(0.7f);
        
        int buttonSize = dpToPx(60);
        // Use FrameLayout.LayoutParams instead of RelativeLayout.LayoutParams
        android.widget.FrameLayout.LayoutParams params = 
            new android.widget.FrameLayout.LayoutParams(buttonSize, buttonSize);
        
        // Set gravity instead of rules for FrameLayout
        params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        params.setMargins(0, 0, dpToPx(20), dpToPx(20));
        
        floatingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToast("Capture triggered");
                capturePhoto();
            }
        });
        
        // Get the content frame as a FrameLayout
        android.widget.FrameLayout rootLayout = (android.widget.FrameLayout) findViewById(android.R.id.content);
        rootLayout.addView(floatingButton, params);
        
        mFloatingButton = floatingButton;
    }
    
    // Utility to convert dp to pixels
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void initSDKComponents() {
        BaseProduct product = DJISampleApplication.getProductInstance();
        if (product == null || !product.isConnected()) {
            ToastUtils.setResultToToast("Product not connected");
            return;
        }
        if (ModuleVerificationUtil.isFlightControllerAvailable()) {
            mFlightController = ((Aircraft) product).getFlightController();
            mFlightController.setStateCallback(this::updateLocationInfo);
        }
        if (ModuleVerificationUtil.isProductModuleAvailable() &&
            DJISampleApplication.getAircraftInstance().getCamera() != null) {
            mCamera = DJISampleApplication.getAircraftInstance().getCamera();
            mCamera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, rc -> {
                if (rc != null) ToastUtils.setResultToToast("Set camera mode failed: " + rc.getDescription());
            });
            mCamera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE, rc -> {
                if (rc != null) ToastUtils.setResultToToast("Set photo mode failed: " + rc.getDescription());
            });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle volume buttons as capture triggers
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || 
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || 
            keyCode == KeyEvent.KEYCODE_CAMERA) {
            showToast("Key capture triggered");
            capturePhoto();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateLocationInfo(FlightControllerState state) {
        double lat = state.getAircraftLocation().getLatitude();
        double lng = state.getAircraftLocation().getLongitude();
        float  alt = state.getAircraftLocation().getAltitude();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLocationInfoTv.setText(String.format(
                    "Lat: %.6f\nLong: %.6f\nAlt: %.1fm", lat, lng, alt
                ));
            }
        });
    }

    private void startLocationUpdates() {
        if (mLocationTimer != null) mLocationTimer.cancel();
        mLocationTimer = new Timer();
        mLocationTimer.schedule(new TimerTask() {
            @Override public void run() { sendLocationToServer(); }
        }, 0, 3000);
    }

    private void sendLocationToServer() {
        if (mFlightController == null) return;
        FlightControllerState s = mFlightController.getState();
        double lat = s.getAircraftLocation().getLatitude();
        double lng = s.getAircraftLocation().getLongitude();
        try {
            JSONObject fields = new JSONObject();
            JSONObject geo = new JSONObject()
                .put("latitude", lat)
                .put("longitude", lng);
            fields.put("droneLocation", new JSONObject().put("geoPointValue", geo));
            fields.put("updatedAt", new JSONObject()
                .put("timestampValue", new Date().toInstant().toString())
            );

            String url = FIRESTORE_BASE_URL
                + "/searchAssignments/" + mAssignmentId
                + "?updateMask.fieldPaths=droneLocation"
                + "&updateMask.fieldPaths=updatedAt";

            Request req = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(
                    new JSONObject().put("fields", fields).toString(), JSON
                ))
                .addHeader("Authorization", "Bearer " + mAuthToken)
                .build();

            mOkHttpClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call c, IOException e) {
                    Log.e(TAG, "Location update failed", e);
                }
                @Override public void onResponse(Call c, Response r) throws IOException {
                    if (!r.isSuccessful()) {
                        Log.e(TAG, "Loc-update error " + r.code() + ": " + r.body().string());
                    }
                    r.close();
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in sendLocation", e);
        }
    }

    private void capturePhoto() {
        if (mCamera == null) { ToastUtils.setResultToToast("Camera not available"); return; }
        
        mCamera.startShootPhoto(rc -> {
            if (rc == null) {
                showToast("Photo captured");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sendCurrentFrameAsPhoto();
                    }
                });
            } else {
                showToast("Capture failed: " + rc.getDescription());
            }
        });
    }

    private void sendCurrentFrameAsPhoto() {
        Bitmap bmp = mVideoSurface.getBitmap();
        if (bmp == null) { showToast("Frame grab failed"); return; }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        String img64 = "data:image/jpeg;base64," +
            android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.DEFAULT);
        appendFindingToEmergency(img64);
    }

    private void appendFindingToEmergency(String imageBase64) {
        if (mFlightController == null) { showToast("FC not available"); return; }
        FlightControllerState s = mFlightController.getState();
        double lat = s.getAircraftLocation().getLatitude();
        double lng = s.getAircraftLocation().getLongitude();

        try {
            // Create a finding document directly in the findings collection
            JSONObject findingData = new JSONObject();
            
            // Core finding data fields
            findingData.put("emergencyId", new JSONObject().put("stringValue", mEmergencyId));
            findingData.put("description", new JSONObject().put("stringValue", "Drone photo from Android"));
            findingData.put("operatorId", new JSONObject().put("stringValue", mUserId));
            
            // Location data (matching the new structure)
            JSONObject locationObj = new JSONObject();
            locationObj.put("latitude", new JSONObject().put("doubleValue", lat));
            locationObj.put("longitude", new JSONObject().put("doubleValue", lng));
            findingData.put("location", new JSONObject().put("mapValue", 
                new JSONObject().put("fields", locationObj)));
            
            // Image (if available)
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                findingData.put("imageBase64", new JSONObject().put("stringValue", imageBase64));
            }
            
            // Timestamp (current time in ISO format)
            findingData.put("timestamp", new JSONObject().put("timestampValue", 
                new Date().toInstant().toString()));
            
            // URL for creating a finding document
            String url = FIRESTORE_BASE_URL + "/findings";
            
            // Create the request with all the fields
            JSONObject requestBody = new JSONObject();
            requestBody.put("fields", findingData);
            
            Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody.toString(), JSON))
                .addHeader("Authorization", "Bearer " + mAuthToken)
                .build();
            
            mOkHttpClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call c, IOException e) {
                    showToast("Add finding failed");
                    Log.e(TAG, "Failed to add finding", e);
                }
                
                @Override public void onResponse(Call c, Response r) throws IOException {
                    if (r.isSuccessful()) {
                        showToast("Finding reported");
                        
                        // After successful finding creation, update the emergency's updatedAt field
                        updateEmergencyTimestamp(mEmergencyId);
                    } else {
                        showToast("Finding error " + r.code());
                        Log.e(TAG, "Error response: " + r.body().string());
                    }
                    r.close();
                }
            });
        } catch (JSONException ex) {
            showToast("JSON parse err");
            Log.e(TAG, "JSON error in creating finding", ex);
        }
    }

    private void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(DroneWatchActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Helper method to update the emergency's timestamp after adding a finding
    private void updateEmergencyTimestamp(String emergencyId) {
        try {
            JSONObject fields = new JSONObject();
            fields.put("updatedAt", new JSONObject()
                .put("timestampValue", new Date().toInstant().toString())
            );

            String url = FIRESTORE_BASE_URL
                + "/emergencies/" + emergencyId
                + "?updateMask.fieldPaths=updatedAt";

            Request req = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(
                    new JSONObject().put("fields", fields).toString(), JSON
                ))
                .addHeader("Authorization", "Bearer " + mAuthToken)
                .build();

            mOkHttpClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call c, IOException e) {
                    Log.e(TAG, "Failed to update emergency timestamp", e);
                }
                
                @Override public void onResponse(Call c, Response r) throws IOException {
                    if (!r.isSuccessful()) {
                        Log.e(TAG, "Error updating emergency: " + r.code());
                    }
                    r.close();
                }
            });
        } catch (JSONException ex) {
            Log.e(TAG, "JSON error in updateEmergencyTimestamp", ex);
        }
    }
    
    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
        if (mCodecManager == null) mCodecManager = new DJICodecManager(this, s, w, h);
        VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mVideoDataListener);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = new DJICodecManager(this, s, w, h);
        }
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }
        VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(mVideoDataListener);
        return false;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { /* no-op */ }
    @Override protected void onResume() {
        super.onResume();
        if (mVideoSurface != null) mVideoSurface.setSurfaceTextureListener(this);
        if (mLocationTimer == null) startLocationUpdates();
    }
    @Override protected void onPause() {
        super.onPause();
        VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(mVideoDataListener);
        if (mLocationTimer != null) { mLocationTimer.cancel(); mLocationTimer = null; }
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (mLocationTimer != null) { mLocationTimer.cancel(); mLocationTimer = null; }
        if (mCodecManager != null) { mCodecManager.cleanSurface(); mCodecManager = null; }
        VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(mVideoDataListener);
    }
}
