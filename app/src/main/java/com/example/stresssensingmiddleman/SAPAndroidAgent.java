package com.example.stresssensingmiddleman;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import android.widget.Toast;

import com.samsung.android.sdk.SsdkUnsupportedException;
import com.samsung.android.sdk.accessory.SA;
import com.samsung.android.sdk.accessory.SAAgentV2;
import com.samsung.android.sdk.accessory.SAPeerAgent;
import com.samsung.android.sdk.accessory.SASocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import inha.nsl.easytrack.ETServiceGrpc;
import inha.nsl.easytrack.EtService;
import inha.nsl.easytrack.library.LocalDBManager;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import static com.example.stresssensingmiddleman.MainActivity.TAG;

public class SAPAndroidAgent extends SAAgentV2 {
    private static final int CHANNEL_ID = 110;

    private SASocket mProviderServiceSocket;
    private ConnectionListener connectionListener;
    private boolean runThreads = true;
    private boolean sent = false;


    public SAPAndroidAgent(Context context) {
        super(TAG, context, ServiceConnection.class);

        LocalDBManager.INSTANCE.init(getApplicationContext());
        SA mAccessory = new SA();
        try {
            mAccessory.initialize(context);
            // heartbeat submission thread
            new Thread(() -> {
                SharedPreferences prefs = getApplicationContext().getApplicationContext().getSharedPreferences(getApplicationContext().getPackageName(), Context.MODE_PRIVATE);
                String grpcHost = context.getString(R.string.grpc_host);
                int grpcPort = Integer.parseInt(context.getString(R.string.grpc_port));
                while (runThreads) {
                    try {
                        String email = prefs.getString("email", null);
                        assert email != null;
                        int userId = prefs.getInt("userId", -1);
                        ManagedChannel channel = ManagedChannelBuilder.forAddress(grpcHost, grpcPort).usePlaintext().build();
                        ETServiceGrpc.ETServiceBlockingStub stub = ETServiceGrpc.newBlockingStub(channel);
                        EtService.SubmitHeartbeatRequestMessage requestMessage = EtService.SubmitHeartbeatRequestMessage.newBuilder()
                                .setUserId(userId)
                                .setEmail(email)
                                .build();
                        try {
                            EtService.DefaultResponseMessage resultMessage = stub.submitHeartbeat(requestMessage);
                            Log.e(TAG, "Heartbeat submission result : doneSuccessfully=" + resultMessage.getDoneSuccessfully());
                        } catch (StatusRuntimeException e) {
                            e.printStackTrace();
                        } finally {
                            channel.shutdown();
                        }
                        Thread.sleep(20000);
                    } catch (Exception e) {
                        Log.e(TAG, "SAPAndroidAgent: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }).start();

            // requesting data
            new Thread(() -> {
                while (runThreads) {
                    try {
                        if (mProviderServiceSocket != null) {
                            boolean sent = sendMessage(new byte[]{1});
                            Log.e(TAG, "Request data from SmartWatch : " + sent);
                        }
                        Thread.sleep(90000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            // data submission thread
            new Thread(() -> {
                SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(getApplicationContext().getPackageName(), Context.MODE_PRIVATE);
                String grpcHost = context.getString(R.string.grpc_host);
                int grpcPort = Integer.parseInt(context.getString(R.string.grpc_port));
                while (runThreads) {
                    try {
                        if (isConnectedToWifi()) {
                            String email = prefs.getString("email", null);
                            int userId = prefs.getInt("userId", -1);
                            ManagedChannel channel = ManagedChannelBuilder.forAddress(grpcHost, grpcPort).usePlaintext().build();
                            ETServiceGrpc.ETServiceBlockingStub stub = ETServiceGrpc.newBlockingStub(channel);

                        /*val cursor = LocalDBManager.sensorData()
                        if (cursor != null && cursor.moveToFirst())
                            do {
                                val request = EtService.SubmitDataRecordRequestMessage.newBuilder()
                                    .setUserId(userId)
                                    .setEmail(email)
                                    .setDataSource(cursor.getInt(cursor.getColumnIndex("dataSourceId")))
                                    .setTimestamp(cursor.getLong(cursor.getColumnIndex("timestamp")))
                                    .setAccuracy(0.0f)
                                    .setValues(cursor.getString(cursor.getColumnIndex("data")))
                                    .build()
                                val result = stub.submitDataRecord(request)
                                if (result.doneSuccessfully)
                                    LocalDBManager.deleteRecord(cursor.getInt(cursor.getColumnIndex("id")))
                            } while (cursor.moveToNext())*/
                            Cursor cursor = LocalDBManager.INSTANCE.sensorData();
                            EtService.SubmitDataRecordsRequestMessage.Builder requestBuilder = EtService.SubmitDataRecordsRequestMessage.newBuilder()
                                    .setUserId(userId)
                                    .setEmail(email);
                            ArrayList<Integer> ids = new ArrayList<>();
                            if (cursor != null && cursor.moveToFirst())
                                do {
                                    ids.add(cursor.getInt(cursor.getColumnIndex("id")));
                                    requestBuilder.addDataSource(cursor.getInt(cursor.getColumnIndex("dataSourceId")));
                                    requestBuilder.addTimestamp(cursor.getLong(cursor.getColumnIndex("timestamp")));
                                    requestBuilder.addValues(cursor.getString(cursor.getColumnIndex("data")));
                                    requestBuilder.addAccuracy(0.0f);

                                    if (ids.size() == 400) {
                                        try {
                                            EtService.DefaultResponseMessage res = stub.submitDataRecords(requestBuilder.build());
                                            if (res.getDoneSuccessfully())
                                                for (int id : ids)
                                                    LocalDBManager.INSTANCE.deleteRecord(id);
                                        } catch (StatusRuntimeException e) {
                                            e.printStackTrace();
                                            break;
                                        }

                                        requestBuilder = EtService.SubmitDataRecordsRequestMessage.newBuilder()
                                                .setUserId(userId)
                                                .setEmail(email);
                                        ids.clear();
                                    }
                                } while (cursor.moveToNext());
                            if (ids.size() > 0) {
                                try {
                                    EtService.DefaultResponseMessage res = stub.submitDataRecords(requestBuilder.build());
                                    if (res.getDoneSuccessfully())
                                        for (int id : ids)
                                            LocalDBManager.INSTANCE.deleteRecord(id);
                                } catch (StatusRuntimeException e) {
                                    e.printStackTrace();
                                    break;
                                }
                            }
                            channel.shutdown();
                            Log.e(TAG, "Data transferred to EasyTrack server!");
                        } else
                            Log.e(TAG, "Couldn't try to submit data because device isn't connected to a WiFi network!");
                        Thread.sleep(60000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        } catch (SsdkUnsupportedException e0) {
            Log.e(TAG, "SsdkUnsupportedException : ${e0.message}");
        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }

    @Override
    protected void onFindPeerAgentsResponse(SAPeerAgent[] peerAgents, int result) {
        if (result == PEER_AGENT_FOUND) {
            for (SAPeerAgent peerAgent : peerAgents)
                requestServiceConnection(peerAgent);
            Log.e(TAG, "Peer agents found");
        } else {
            Log.e(TAG, "Failed to find a peer agent : " + result);
        }
    }

    @Override
    protected void onServiceConnectionRequested(SAPeerAgent peerAgent) {
        if (peerAgent != null) {
            acceptServiceConnectionRequest(peerAgent);
            Log.e(TAG, "Incoming service connection request accepted");
        }
    }

    @Override
    protected void onServiceConnectionResponse(SAPeerAgent peerAgent, SASocket socket, int result) {
        switch (result) {
            case CONNECTION_SUCCESS:
                mProviderServiceSocket = socket;
                if (connectionListener != null)
                    connectionListener.run(true);
                Toast.makeText(getApplicationContext(), "Gear connection is established", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Gear S3 connection established");
                break;
            case CONNECTION_ALREADY_EXIST:
                mProviderServiceSocket = socket;
                if (connectionListener != null)
                    connectionListener.run(true);
                Toast.makeText(getApplicationContext(), "Gear connection already exists", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Gear S3 connection established");
                break;
            default:
                Log.e(TAG, "Failed to establish Gear S3 connection : " + result);
                break;
        }
    }

    @Override
    protected void onPeerAgentsUpdated(SAPeerAgent[] peerAgents, int result) {
        if (peerAgents != null)
            if (result == PEER_AGENT_AVAILABLE) {
                for (SAPeerAgent peerAgent : peerAgents)
                    requestServiceConnection(peerAgent);
            }
    }

    @Override
    protected void onError(SAPeerAgent peerAgent, String message, int result) {
        super.onError(peerAgent, message, result);
        runThreads = false;
    }

    private boolean sendMessage(byte[] bytes) {
        sent = false;
        Thread thread = new Thread(() -> {
            try {
                mProviderServiceSocket.send(CHANNEL_ID, bytes);
                sent = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return sent;
    }

    private boolean isConnectedToWifi() {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.getTypeName() != null && activeNetwork.getTypeName().toLowerCase().equals("wifi"))
                return activeNetwork.isConnected();
        }
        return false;
    }


    interface ConnectionListener {
        void run(boolean connected);
    }

    void setConnectionListener(ConnectionListener listener) {
        listener.run(mProviderServiceSocket != null);
        connectionListener = listener;
    }
}
