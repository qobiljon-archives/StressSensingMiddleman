package com.example.stresssensingmiddleman;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.samsung.android.sdk.accessory.SAAgentV2;

import java.util.Calendar;

import inha.nsl.easytrack.ETServiceGrpc;
import inha.nsl.easytrack.EtService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

public class MainActivity extends AppCompatActivity {
    static final String TAG = "SAPAndroidAgent";
    static final int RC_OPEN_ET_AUTHENTICATOR = 100;
    static final int RC_OPEN_APP_STORE = 101;

    private SAPAndroidAgent sapAndroidAgent;
    private boolean connectedToWatch = false;
    private TextView textView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textView = findViewById(R.id.textView);

        if (authAppIsNotInstalled()) {
            Toast.makeText(this, "Please install this app and reopen the application!", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=inha.nsl.easytrack"));
            intent.setPackage("com.android.vending");
            startActivityForResult(intent, RC_OPEN_APP_STORE);
            finish();
        } else {
            SharedPreferences prefs = getSharedPreferences(getPackageName(), MODE_PRIVATE);
            if (prefs.getInt("userId", -1) == -1 || prefs.getString("email", null) == null) {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage("inha.nsl.easytrack");
                if (launchIntent != null) {
                    launchIntent.setFlags(0);
                    startActivityForResult(launchIntent, RC_OPEN_ET_AUTHENTICATOR);
                }
            } else {
                startSAPAgent();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (connectedToWatch) {
            sapAndroidAgent.releaseAgent();
            connectedToWatch = false;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == RC_OPEN_ET_AUTHENTICATOR) {
            if (resultCode == RESULT_OK && data != null) {
                String fullName = data.getStringExtra("fullName");
                String email = data.getStringExtra("email");
                assert email != null;
                int userId = data.getIntExtra("userId", -1);
                new Thread(() -> {
                    ManagedChannel channel = ManagedChannelBuilder.forAddress(getString(R.string.grpc_host), Integer.parseInt(getString(R.string.grpc_port))).usePlaintext().build();
                    ETServiceGrpc.ETServiceBlockingStub stub = ETServiceGrpc.newBlockingStub(channel);
                    EtService.BindUserToCampaignRequestMessage requestMessage = EtService.BindUserToCampaignRequestMessage.newBuilder()
                            .setUserId(userId)
                            .setEmail(email)
                            .setCampaignId(Integer.parseInt(getString(R.string.stress_sensing_campaign_id)))
                            .build();
                    try {
                        EtService.BindUserToCampaignResponseMessage responseMessage = stub.bindUserToCampaign(requestMessage);
                        if (responseMessage.getDoneSuccessfully()) runOnUiThread(() -> {
                            SharedPreferences prefs = getSharedPreferences(getPackageName(), Context.MODE_PRIVATE);
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putString("fullName", fullName);
                            editor.putString("email", email);
                            editor.putInt("userId", userId);
                            editor.apply();
                            Toast.makeText(getApplicationContext(), "Successfully authorized and connected to the stress sensing campaign!", Toast.LENGTH_SHORT).show();
                            startSAPAgent();
                        });
                        else runOnUiThread(() -> {
                            Calendar cal = Calendar.getInstance();
                            cal.setTimeInMillis(responseMessage.getCampaignStartTimestamp());
                            Toast.makeText(getApplicationContext(), "Campaign hasn't started yet (start time : ${SimpleDateFormat.getDateTimeInstance().format(cal.time)}", Toast.LENGTH_SHORT).show();
                        });
                    } catch (StatusRuntimeException e) {
                        runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Error occurred when joining to the campaign, please try again later!", Toast.LENGTH_SHORT).show());
                        e.printStackTrace();
                    } finally {
                        channel.shutdown();
                    }
                }).start();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }


    private boolean authAppIsNotInstalled() {
        try {
            getPackageManager().getPackageInfo("inha.nsl.easytrack", 0);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private void startSAPAgent() {
        SAAgentV2.requestAgent(getApplicationContext(), SAPAndroidAgent.class.getName(), new SAAgentV2.RequestAgentCallback() {
            @Override
            public void onAgentAvailable(SAAgentV2 agent) {
                if (agent != null) {
                    sapAndroidAgent = (SAPAndroidAgent) agent;
                    textView.append("Ready to accept connection =)\n");
                    sapAndroidAgent.setConnectionListener((boolean connectedToSmartwatch) -> {
                        MainActivity.this.connectedToWatch = connectedToSmartwatch;
                        if (connectedToWatch)
                            try {
                                textView.append("Device connected =)\n");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                    });
                } else {
                    textView.append("Agent unavailable =(\n");
                }
            }

            @Override
            public void onError(int errorCode, String message) {
                textView.append("Failed to get agent =(");
                Log.e(TAG, "Failed to get agent : code(" + errorCode + "), message(" + message + ")");
            }
        });
    }
}
