// IShellcodeReporter.aidl
package com.example.leakvalue;

// Declare any non-default types here with import statements

interface IShellcodeReporter {
    void noteShellcodeExecuted(String packageName, String id);
}