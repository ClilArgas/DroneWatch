package com.dji.sdk.sample.demo.dronewatch;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.view.PresentableView;

/**
 * This view will be displayed in the SDK Demo app to launch the DroneWatch activity
 */
public class DroneWatchView extends LinearLayout implements PresentableView {

    private Button mStartButton;

    public DroneWatchView(Context context) {
        super(context);
        initUI(context);
    }

    private void initUI(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater layoutInflater = (LayoutInflater) context.getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_drone_watch, this, true);

        mStartButton = findViewById(R.id.btn_open_drone_watch);
        mStartButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getContext(), LoginActivity.class);
                getContext().startActivity(intent);
            }
        });
    }

    @Override
    public int getDescription() {
        return R.string.drone_watch_description;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    @Override
    public String getHint() {
        return getContext().getString(R.string.drone_watch_hint);
    }
}