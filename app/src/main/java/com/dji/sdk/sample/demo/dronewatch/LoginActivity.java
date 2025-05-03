package com.dji.sdk.sample.demo.dronewatch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.dji.sdk.sample.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginActivity extends Activity {
    private static final String TAG = "LoginActivity";

    // Firebase Auth REST endpoint
    private static final String FIREBASE_AUTH_URL =
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=AIzaSyBfKHQoMhVbLrCm8qZ_C_B-ASYNDKPduPE";

    private EditText    mEmailEditText;
    private EditText    mPasswordEditText;
    private Button      mLoginButton;
    private ProgressBar mProgressBar;
    private TextView    mErrorTextView;
    private OkHttpClient mOkHttpClient;

    // SharedPreferences keys
    private SharedPreferences mPrefs;
    private static final String PREF_FILE                  = "aeroaid_prefs";
    private static final String PREF_TOKEN                 = "auth_token";
    private static final String PREF_USER_ID               = "user_id";
    private static final String PREF_EMAIL                 = "user_email";
    private static final String PREF_EMERGENCY_ID          = "emergencyId";
    private static final String PREF_ASSIGNMENT_ID         = "currentAssignmentId";

    // Maximum number of attempts to avoid spamming
    private static final int MAX_ATTEMPTS = 3;
    private int mAttemptCount = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) Inflate layout
        setContentView(R.layout.activity_login);

        // 2) Bind all UI widgets before doing any logic
        mEmailEditText   = findViewById(R.id.et_email);
        mPasswordEditText= findViewById(R.id.et_password);
        mLoginButton     = findViewById(R.id.btn_login);
        mProgressBar     = findViewById(R.id.progress_bar);
        mErrorTextView   = findViewById(R.id.tv_error);

        // 3) Prepare networking & prefs
        mOkHttpClient = new OkHttpClient();
        mPrefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);

        // Clear previous assignment data on fresh start
        mPrefs.edit()
            .remove(PREF_EMERGENCY_ID)
            .remove(PREF_ASSIGNMENT_ID)
            .apply();

        // 4) If we already have a token, go straight to checkForActiveEmergency()
        String token = mPrefs.getString(PREF_TOKEN, null);
        if (token != null) {
            checkForActiveEmergency();
            return;
        }

        // 5) Otherwise hook up login button
        mLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                attemptLogin();
            }
        });
    }

    private void attemptLogin() {
        final String email    = mEmailEditText.getText().toString().trim();
        final String password = mPasswordEditText.getText().toString().trim();

        if (email.isEmpty()) {
            showError("Email is required");
            return;
        }
        if (password.isEmpty()) {
            showError("Password is required");
            return;
        }

        // Show loading state
        mLoginButton.setEnabled(false);
        mProgressBar.setVisibility(View.VISIBLE);
        mErrorTextView.setVisibility(View.GONE);

        try {
            JSONObject loginData = new JSONObject();
            loginData.put("email", email);
            loginData.put("password", password);
            loginData.put("returnSecureToken", true);

            RequestBody body = RequestBody.create(
                MediaType.parse("application/json"),
                loginData.toString()
            );

            Request request = new Request.Builder()
                .url(FIREBASE_AUTH_URL)
                .post(body)
                .build();

            mOkHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showError("Network error: " + e.getMessage());
                            mLoginButton.setEnabled(true);
                            mProgressBar.setVisibility(View.GONE);
                        }
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    final String responseBody = response.body().string();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (response.isSuccessful()) {
                                    JSONObject json = new JSONObject(responseBody);
                                    String idToken = json.getString("idToken");
                                    String userId  = json.getString("localId");

                                    // Reset attempt counter on successful login
                                    mAttemptCount = 0;

                                    mPrefs.edit()
                                          .putString(PREF_TOKEN, idToken)
                                          .putString(PREF_USER_ID, userId)
                                          .putString(PREF_EMAIL, email)
                                          .apply();

                                    checkForActiveEmergency();
                                } else {
                                    JSONObject err = new JSONObject(responseBody);
                                    String msg = "Invalid email or password";
                                    if (err.has("error") && err.getJSONObject("error").has("message")) {
                                        String code = err.getJSONObject("error").getString("message");
                                        switch (code) {
                                            case "EMAIL_NOT_FOUND":   msg = "Email not found";      break;
                                            case "INVALID_PASSWORD":   msg = "Incorrect password"; break;
                                            case "USER_DISABLED":      msg = "Account disabled";   break;
                                            default:                   msg = "Login failed: " + code;
                                        }
                                    }
                                    showError(msg);
                                    mLoginButton.setEnabled(true);
                                    mProgressBar.setVisibility(View.GONE);
                                }
                            } catch (JSONException e) {
                                showError("Error processing server response");
                                mLoginButton.setEnabled(true);
                                mProgressBar.setVisibility(View.GONE);
                            }
                        }
                    });
                }
            });
        } catch (JSONException e) {
            showError("Error preparing login request");
            mLoginButton.setEnabled(true);
            mProgressBar.setVisibility(View.GONE);
        }
    }

    private void checkForActiveEmergency() {
        // Increment attempt counter to prevent infinite loops
        mAttemptCount++;
        if (mAttemptCount > MAX_ATTEMPTS) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mProgressBar.setVisibility(View.GONE);
                    showNoActiveEmergency();
                }
            });
            return;
        }

        final String userId = mPrefs.getString(PREF_USER_ID, null);
        final String token  = mPrefs.getString(PREF_TOKEN, null);
        if (userId == null || token == null) {
            showError("Authentication data missing");
            return;
        }

        // Show loading
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mProgressBar.setVisibility(View.VISIBLE);
            }
        });

        // Direct Firestore REST API URL
        String url = "https://firestore.googleapis.com/v1/projects/aeroaid-39329/databases/(default)/documents/users/" + userId;
        
        Log.d(TAG, "Fetching user data from: " + url);

        Request request = new Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer " + token)
            .build();

        mOkHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                final String errorMsg = e.getMessage();
                Log.e(TAG, "Failed to fetch user data: " + errorMsg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showError("Failed to fetch user data: " + errorMsg);
                        mProgressBar.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body = response.body().string();
                Log.d(TAG, "User data response: " + body);
                
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (response.isSuccessful()) {
                                JSONObject userDoc = new JSONObject(body);
                                JSONObject fields = userDoc.getJSONObject("fields");

                                // Check if user is a drone operator
                                boolean isOp = false;
                                if (fields.has("isDroneOperator")) {
                                    JSONObject isDroneOperatorObj = fields.getJSONObject("isDroneOperator");
                                    if (isDroneOperatorObj.has("booleanValue")) {
                                        isOp = isDroneOperatorObj.getBoolean("booleanValue");
                                    }
                                }

                                if (!isOp) {
                                    showError("Your account does not have drone operator permissions");
                                    mProgressBar.setVisibility(View.GONE);
                                    return;
                                }

                                // Get emergency ID and assignment ID
                                String emergencyId = "";
                                String assignmentId = "";

                                // Extract emergencyId if it exists
                                if (fields.has("emergencyId")) {
                                    JSONObject emergencyIdObj = fields.getJSONObject("emergencyId");
                                    if (emergencyIdObj.has("stringValue")) {
                                        emergencyId = emergencyIdObj.getString("stringValue");
                                    }
                                }

                                // Extract currentAssignmentId if it exists
                                if (fields.has("currentAssignmentId")) {
                                    JSONObject assignmentIdObj = fields.getJSONObject("currentAssignmentId");
                                    if (assignmentIdObj.has("stringValue")) {
                                        assignmentId = assignmentIdObj.getString("stringValue");
                                    }
                                }

                                Log.d(TAG, "Extracted emergencyId: " + emergencyId + ", assignmentId: " + assignmentId);

                                if (!emergencyId.isEmpty() && !assignmentId.isEmpty()) {
                                    // Save to preferences
                                    mPrefs.edit()
                                        .putString(PREF_EMERGENCY_ID, emergencyId)
                                        .putString(PREF_ASSIGNMENT_ID, assignmentId)
                                        .apply();
                                    
                                    startDroneWatchActivity();
                                } else {
                                    // Clear any old values
                                    mPrefs.edit()
                                        .remove(PREF_EMERGENCY_ID)
                                        .remove(PREF_ASSIGNMENT_ID)
                                        .apply();
                                    
                                    showNoActiveEmergency();
                                }
                            } else {
                                Log.e(TAG, "Failed to fetch user data. Status code: " + response.code());
                                showError("Failed to fetch user data: " + response.code() + " " + response.message());
                                mProgressBar.setVisibility(View.GONE);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "Error processing user data: " + e.getMessage());
                            showError("Error processing user data");
                            mProgressBar.setVisibility(View.GONE);
                        }
                    }
                });
            }
        });
    }

    private void showNoActiveEmergency() {
        mProgressBar.setVisibility(View.GONE);
        new android.app.AlertDialog.Builder(this)
            .setTitle("No Active Emergency")
            .setMessage("You don't have an active emergency assigned. Please accept one in the web app first.")
            .setPositiveButton("OK", (dialog, which) -> {
                dialog.dismiss();
                mLoginButton.setEnabled(true);
            })
            .setCancelable(false)
            .show();
    }

    private void startDroneWatchActivity() {
        Log.d(TAG, "Starting DroneWatchActivity");
        Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show();
        Intent i = new Intent(LoginActivity.this, DroneWatchActivity.class);
        startActivity(i);
        finish();
    }

    private void showError(String message) {
        Log.e(TAG, "Error: " + message);
        mErrorTextView.setText(message);
        mErrorTextView.setVisibility(View.VISIBLE);
    }
}