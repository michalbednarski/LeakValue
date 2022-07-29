package com.example.leakvalue.localtest;

import static android.content.Context.BIND_AUTO_CREATE;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.session.MediaSessionManager;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import java.lang.reflect.Field;
import java.util.Arrays;

public class TestModel extends AndroidViewModel {

    private final ServiceConnection mServiceConnection;

    ITestService mTestService;

    public TestModel(@NonNull Application application) {
        super(application);

        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                mTestService = ITestService.Stub.asInterface(iBinder);
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
            }
        };
        application.bindService(new Intent(application, TestService.class), mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onCleared() {
        getApplication().unbindService(mServiceConnection);
        super.onCleared();
    }

    public Context createTestContext() throws RemoteException, ReflectiveOperationException {
        return new MyContextWrapper(getApplication(), mTestService.getLocalMediaSessionService());
    }

    private static class MyContextWrapper extends ContextWrapper {

        private final MediaSessionManager mMediaSessionManager;

        @SuppressWarnings("JavaReflectionMemberAccess")
        @SuppressLint("PrivateApi,SoonBlockedPrivateApi")
        public MyContextWrapper(Application application, IBinder mediaSessionServiceBinder) throws ReflectiveOperationException {
            super(application);
            mMediaSessionManager = MediaSessionManager.class.getConstructor(Context.class).newInstance(this);
            Field serviceField = MediaSessionManager.class.getDeclaredField("mService");
            serviceField.setAccessible(true);
            Object service = serviceField.get(mMediaSessionManager);
            Field remoteField = service.getClass().getDeclaredField("mRemote");
            remoteField.setAccessible(true);
            remoteField.set(service, mediaSessionServiceBinder);
        }

        @Override
        public Object getSystemService(String name) {
            if (MEDIA_SESSION_SERVICE.equals(name)) {
                return mMediaSessionManager;
            }
            return super.getSystemService(name);
        }
    }

    public void startMockAnotherTransaction() throws RemoteException {
        int[] filler = new int[200];
        Arrays.fill(filler, 12345678);
        mTestService.startLongRunningOpWithSensitiveArgument(filler);
    }

    public void finishMockAnotherTransaction() throws RemoteException {
        mTestService.finishLongRunningOp();
    }
}
