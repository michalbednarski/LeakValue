package com.example;

public class MockPackageManager extends android.content.pm.PackageManagerInternal {
	private int mMockUid;
	public MockPackageManager(int mockUid) { mMockUid = mockUid; }
	public int getPackageUid(String packageName, int flags, int userId) { return mMockUid; }
	public int getPackageUid(String packageName, long flags, int userId) { return mMockUid; }
}
