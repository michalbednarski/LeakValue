package com.example.leakvalue.localtest;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.media.AudioManager;
import android.os.BaseBundle;
import android.os.ConditionVariable;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.system.Os;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import com.example.leakvalue.MiscUtils;
import com.example.leakvalue.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Objects;

import dalvik.system.PathClassLoader;

public class TestService extends Service {

    private IBinder mMediaSessionServiceBinder;
    private ConditionVariable mConditionVariable = new ConditionVariable();

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            MiscUtils.allowHiddenApis();

            // Enable Bundle defusing
            BaseBundle.class
                    .getMethod("setShouldDefuse", boolean.class)
                    .invoke(null, true);

            // Prepare ClassLoader for system_server and mock classes
            File mockSystemClassesDir = getDir("mock_system", MODE_PRIVATE);
            File mockSystemClassesDex = new File(mockSystemClassesDir, "classes.dex");
            try (
                    InputStream inputStream = getResources().openRawResource(R.raw.mock_system);
                    OutputStream outputStream = new FileOutputStream(mockSystemClassesDex)
            ) {
                FileUtils.copy(inputStream, outputStream);
            }
            PathClassLoader loader = new PathClassLoader(
                    Os.getenv("SYSTEMSERVERCLASSPATH") + ":" + mockSystemClassesDex.getAbsolutePath(),
                    TestService.class.getClassLoader()
            );

            // Mock Package Manager
            Class<?> localServicesClass = loader.loadClass("com.android.server.LocalServices");
            Class<?> packageManagerInternalClass = loader.loadClass("android.content.pm.PackageManagerInternal");
            localServicesClass
                    .getMethod("addService", Class.class, Object.class)
                    .invoke(
                            null,
                            packageManagerInternalClass,
                            loader
                                    .loadClass("com.example.MockPackageManager")
                                    .getConstructor(int.class)
                                    .newInstance(Process.myUid())
                        )
            ;

            // Instantiate MediaSessionService
            Class<?> aClass = loader.loadClass("com.android.server.media.MediaSessionService");
            Object mediaSessionService = aClass.getConstructor(Context.class).newInstance(this);

            // Prepare mock AudioPlayerStateMonitor
            ContextWrapper mockAudioContext = new ContextWrapper(null) {
                @Override
                public Object getSystemService(String name) {
                    if (AUDIO_SERVICE.equals(name)) {
                        try {
                            AudioManager audioManager = AudioManager.class.newInstance();
                            Field playbackCallbackListField = AudioManager.class.getDeclaredField("mPlaybackCallbackList");
                            playbackCallbackListField.setAccessible(true);
                            ArrayList<Object> arrayList = new ArrayList<>();
                            Constructor<?> itemConstructor =
                                    Class.forName("android.media.AudioManager$AudioPlaybackCallbackInfo")
                                            .getDeclaredConstructor(AudioManager.AudioPlaybackCallback.class, Handler.class);
                            itemConstructor.setAccessible(true);
                            arrayList.add(itemConstructor.newInstance(new Object[2]));
                            playbackCallbackListField.set(audioManager, arrayList);
                            return audioManager;
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    throw new IllegalArgumentException();
                }
            };
            Class<?> stateMonitorClass = loader.loadClass("com.android.server.media.AudioPlayerStateMonitor");
            Constructor<?> stateMonitorConstructor = stateMonitorClass.getDeclaredConstructor(Context.class);
            stateMonitorConstructor.setAccessible(true);
            Field stateMonitorField = aClass.getDeclaredField("mAudioPlayerStateMonitor");
            stateMonitorField.setAccessible(true);
            stateMonitorField.set(mediaSessionService, stateMonitorConstructor.newInstance(mockAudioContext));

            // Prepare user record
            Class<?> userRecordClass = loader.loadClass("com.android.server.media.MediaSessionService$FullUserRecord");
            Constructor<?> userRecordConstructor = userRecordClass.getDeclaredConstructor(aClass, int.class);
            userRecordConstructor.setAccessible(true);

            int userId = MiscUtils.myUserId();
            Field fullUserIdsField = aClass.getDeclaredField("mFullUserIds");
            fullUserIdsField.setAccessible(true);
            ((SparseIntArray) fullUserIdsField.get(mediaSessionService)).put(userId, userId);
            Field userRecordsField = aClass.getDeclaredField("mUserRecords");
            userRecordsField.setAccessible(true);
            ((SparseArray) userRecordsField.get(mediaSessionService)).put(userId, userRecordConstructor.newInstance(mediaSessionService, userId));

            // Start thread
            Field recordThreadField = aClass.getDeclaredField("mRecordThread");
            recordThreadField.setAccessible(true);
            ((HandlerThread) recordThreadField.get(mediaSessionService)).start();

            // Publish Binder
            Field implField = aClass.getDeclaredField("mSessionManagerImpl");
            mMediaSessionServiceBinder = (IBinder) implField.get(mediaSessionService);
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new ITestService.Stub() {
            @Override
            public IBinder getLocalMediaSessionService() {
                return mMediaSessionServiceBinder;
            }

            @Override
            public void startLongRunningOpWithSensitiveArgument(int[] mockSensitiveData) {
                mConditionVariable.close();
                mConditionVariable.block();
            }

            @Override
            public void finishLongRunningOp() {
                mConditionVariable.open();
            }
        };
    }
}
