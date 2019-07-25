package com.wongfei.sensorbroadcaster;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.net.SocketAddress;

public class MainActivity extends AppCompatActivity {

    private final static String TAG = "SensorBroadcaster";
    private final static String DefaultPort = "9999";
    private final static String DefaultPassword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);

        // config

        final TextView textPort = findViewById(R.id.port);
        textPort.setText(sharedPref.getString("port", DefaultPort));

        final TextView textPassword = findViewById(R.id.password);
        textPassword.setText(sharedPref.getString("password", DefaultPassword));

        // start/stop

        final Switch swStart = findViewById(R.id.start);
        swStart.setChecked(SensorBroadcasterService.getInstance() != null);

        swStart.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                boolean isRunning = SensorBroadcasterService.getInstance() != null;
                if (isChecked && !isRunning) {
                    startServiceInstance();
                } else if (!isChecked && isRunning) {
                    stopServiceInstance();
                }
            }
        });

        // status

        final TextView textClientAddr = findViewById(R.id.clientAddr);
        final TextView textNetStatus = findViewById(R.id.netStatus);

        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                SensorBroadcasterService service = SensorBroadcasterService.getInstance();
                if (service != null) {
                    SocketAddress addr = service.getClientAddr();
                    textClientAddr.setText(addr != null ? addr.toString() : "waiting connection");
                    textNetStatus.setText("pk:" + service.getTotalPacketsSent() + " bytes:" + service.getTotalBytesSent());
                } else {
                    textClientAddr.setText("offline");
                    textNetStatus.setText("-");
                }
                handler.postDelayed(this, 500);
            }
        }, 500);
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");

        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        final TextView textPort = findViewById(R.id.port);
        editor.putString("port", textPort.getText().toString());

        final TextView textPassword = findViewById(R.id.password);
        editor.putString("password", textPassword.getText().toString());

        editor.commit();

        super.onStop();
    }

    private final void startServiceInstance() {
        Log.d(TAG, "startServiceInstance");

        Intent service = new Intent(MainActivity.this, SensorBroadcasterService.class);
        service.putExtra("port", ((EditText) findViewById(R.id.port)).getText().toString());
        service.putExtra("password", ((EditText) findViewById(R.id.password)).getText().toString());
        startService(service);
    }

    private final void stopServiceInstance() {
        Log.d(TAG, "stopServiceInstance");

        Intent service = new Intent(MainActivity.this, SensorBroadcasterService.class);
        stopService(service);
    }
}
