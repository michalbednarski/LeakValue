package android.content.pm;

public abstract class PackageManagerInternal {
	public abstract int getPackageUid(String packageName, int flags, int userId);
	public abstract int getPackageUid(String packageName, long flags, int userId);
}
