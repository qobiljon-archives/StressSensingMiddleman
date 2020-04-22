package com.example.stresssensingmiddleman;

import android.util.Log;

import com.samsung.android.sdk.accessory.SASocket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import inha.nsl.easytrack.library.LocalDBManager;

import static com.example.stresssensingmiddleman.MainActivity.TAG;

public class ServiceConnection extends SASocket {
    public ServiceConnection() {
        super(ServiceConnection.class.getName());
        Log.e(TAG, "ServiceConnection created");
    }

    @Override
    public void onReceive(int channelId, byte[] bytes) {
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
        for (String line : lines) {
            String[] elements = line.split(",");
            if (elements.length < 3)
                continue;
            int dataSource = Integer.parseInt(elements[0]);
            long timestamp = Long.parseLong(elements[1]);
            StringBuilder values = new StringBuilder();
            for (String value : Arrays.copyOfRange(elements, 2, elements.length))
                values.append(value).append(',');
            if (values.length() > 0)
                values.replace(values.length() - 1, values.length(), "");
            LocalDBManager.INSTANCE.saveMixedData(dataSource, timestamp, 0.0f, values);
        }
        Log.e(TAG, "Received data from smartwatch (" + Math.max(lines.length - 1, 0) + " samples)");
    }

    @Override
    public void onError(int channelId, String errorMessage, int errorCode) {
        Log.e(TAG, "Error : channel(" + channelId + "), errorCode(" + errorCode + "), message(" + errorMessage + ")");
    }

    @Override
    protected void onServiceConnectionLost(int errorCode) {
        Log.e(TAG, "Service connection lost, errorCode : " + errorCode);
    }
}
