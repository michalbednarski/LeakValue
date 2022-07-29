// ITestService.aidl
package com.example.leakvalue.localtest;

// Declare any non-default types here with import statements

interface ITestService {
    IBinder getLocalMediaSessionService();

    oneway void startLongRunningOpWithSensitiveArgument(in int[] mockSensitiveData);
    void finishLongRunningOp();
}