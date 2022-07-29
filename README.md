Android 13 introduces many enhancements in order to harden `Parcel` serialization mechanism

[Here's presentation from Android Security and Privacy team on enhancements made](https://www.blackhat.com/eu-22/briefings/schedule/index.html#android-parcels-the-bad-the-good-and-the-better---introducing-androids-safer-parcel-28404)

That is great, definitely eliminates or makes unexploitable many vulnerabilities. Also they describe breaking [my previous exploit, allowing apps to load their code into other apps (including system ones)](https://github.com/michalbednarski/ReparcelBug2)

But now I am back with new exploit that achieves the same, although in different way. It relies on following vulnerabilities that were introduced during aforementioned `Parcel` hardening:

* CVE-2022-20452 ([bulletin](https://source.android.com/docs/security/bulletin/2022-11-01#framework), [patch](https://android.googlesource.com/platform/frameworks/base/+/1aae720772a86e2db682d2e9ed77937334e475f3%5E%21/))
* CVE-2022-20474 ([bulletin](https://source.android.com/docs/security/bulletin/2022-12-01#framework), [patch](https://android.googlesource.com/platform/frameworks/base/+/569c3023f839bca077cd3cccef0a3bef9c31af63%5E%21/))

![Screenshot of application displaying text. Title: LeakValue. Main text: Created 6 ValueLeaker-s. Locking ActivityTaskManagerService. Locked ActivityTaskManagerService. Unlocking ActivityTaskManagerService. Unlocked ActivityTaskManagerService. leakedBinders=[android.os.BinderProxy@f06702e]. Leaked interface: android.app.IApplicationThread. Requesting code execution. Shellcode has been executed in uid=1000 pid=6904 packageName=com.android.settings uid=1000(system) gid=1000(system) groups=1000(system),1007(log),1065(reserved_disk),1077(external_storage),3001(net_bt_admin),3002(net_bt),3003(inet),3007(net_bw_acct),9997(everybody) context=u:r:system_app:s0. At bottom of screen there are two buttons: START and MANUAL TESTING](Screenshot_20220723-081920.png)

[(Also `logcat` from app execution, exploitation is noisy in logs)](logcat.txt)

# Introduction to `Parcel` and `Parcelable` mismatch bugs

Android's [`Parcel`](https://developer.android.com/reference/android/os/Parcel) class is base of communication between processes

Objects can implement `Parcelable` interface in order to allow writing them to `Parcel`, for example ([copied from AOSP](https://cs.android.com/android/platform/superproject/+/android-12.1.0_r8:frameworks/base/core/java/android/hardware/usb/UsbAccessory.java;l=220-251;bpv=0;bpt=1)):

```java
public class UsbAccessory implements Parcelable {
    public static final Parcelable.Creator<UsbAccessory> CREATOR =
        new Parcelable.Creator<UsbAccessory>() {
        public UsbAccessory createFromParcel(Parcel in) {
            String manufacturer = in.readString();
            String model = in.readString();
            String description = in.readString();
            String version = in.readString();
            String uri = in.readString();
            IUsbSerialReader serialNumberReader = IUsbSerialReader.Stub.asInterface(
                    in.readStrongBinder());

            return new UsbAccessory(manufacturer, model, description, version, uri,
                    serialNumberReader);
        }
    };

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mManufacturer);
        parcel.writeString(mModel);
        parcel.writeString(mDescription);
        parcel.writeString(mVersion);
        parcel.writeString(mUri);
        parcel.writeStrongBinder(mSerialNumberReader.asBinder());
   }
}
```

Note that `Parcel` internally stores position at which write or read is performed, `readString()` parses data into String as well as advances position. That position can be manually get/set through [`dataPosition()`](https://developer.android.com/reference/android/os/Parcel#dataPosition())/[`setDataPosition()`](https://developer.android.com/reference/android/os/Parcel#setDataPosition(int)). Implementations of `Parcelable` interface must ensure that their `writeToParcel` and `createFromParcel` write/read same amount of data, otherwise all subsequent reads will get data from wrong offsets

[`Bundle`](https://developer.android.com/reference/android/os/Bundle) (key-value map that can be sent across processes) can contain [variety of objects that can be written to Parcel through `writeValue()`](https://cs.android.com/android/platform/superproject/+/android-12.1.0_r8:frameworks/base/core/java/android/os/Parcel.java;l=1792-1937). When contents of `Bundle` are read from `Parcel`, any `Parcelable` class available in system there can be read

`Bundle` defers actual parsing of contents by having length of whole parcelled data written into `Parcel` and then just [copying relevant part of original Parcel to secondary Parcel stored in `mParcelledData`](https://cs.android.com/android/platform/superproject/+/android-12.1.0_r8:frameworks/base/core/java/android/os/BaseBundle.java;l=1675-1683) (this allows for example [`Activity.onSaveInstanceState()`](https://developer.android.com/reference/android/app/Activity#onSaveInstanceState(android.os.Bundle)) to provide `Parcelable`s which are not available in `system_server`, whole `Bundle` is then passed to `system_server` and back verbatim without parsing contents)

Once however any value in `Bundle` was accessed, all values inside `Bundle` [were unparcelled](https://cs.android.com/android/platform/superproject/+/android-12.1.0_r8:frameworks/base/core/java/android/os/BaseBundle.java;l=227-313) and [every present key-value pair was parsed](https://cs.android.com/android/platform/superproject/+/android-12.1.0_r8:frameworks/base/core/java/android/os/Parcel.java;l=3613-3632). If such map contained `Parcelable` which had unbalanced `writeToParcel` and `createFromParcel` methods and later such `Bundle` was forwarded to another process, that another process could see different contents of `Bundle`. This made all such [mismatches in classes available in system vulnerabilities](https://github.com/michalbednarski/ReparcelBug) as there are [places in system where `Bundle` is inspected to be safe](https://cs.android.com/android/platform/superproject/+/android-12.1.0_r8:frameworks/base/services/core/java/com/android/server/accounts/AccountManagerService.java;l=5037-5046) and then forwarded to another process

In this writeup I'm calling such `Bundle` which presents one contents and then other after being forwarded a self-changing `Bundle`

Another important thing here is that besides just bytes (Strings, numbers, objects made of above), `Parcel` can also contain File Descriptors and `Binder`s. `Binder`s are objects on which one can make RPC call, that is one process creates `Binder` object and overrides [`onTransact()` method](https://developer.android.com/reference/android/os/Binder#onTransact(int,%20android.os.Parcel,%20android.os.Parcel,%20int)). Then `Binder` is passed to another process, in example code above you can see `read`/`writeStrongBinder()` calls used to read and write it to `Parcel`. In another process, when `readStrongBinder()` is used a `BinderProxy` object is created (hidden behind [`IBinder` interface](https://developer.android.com/reference/android/os/IBinder)). Then that another process can call [`transact()`](https://developer.android.com/reference/android/os/IBinder#transact(int,%20android.os.Parcel,%20android.os.Parcel,%20int)) on that object and in original object `onTransact()` will be executed. Usually through, one doesn't manually write `transact()`/`onTransact()` but [uses AIDL instead](https://developer.android.com/guide/components/aidl)

# Enter `LazyValue`, the end of self-changing `Bundle`s

Since in the past there were many cases of classes with `writeToParcel`/`createFromParcel` mismatch, Android 13 solves problem of any such class being present anywhere in system allowing construction of self-changing `Bundle` by [introducing `LazyValue`](https://android.googlesource.com/platform/frameworks/base/+/9ca6a5e21a1987fd3800a899c1384b22d23b6dee%5E%21/)

Now, when `writeValue` is used, if value being written is not primitive, [length of value is written to `Parcel` as well](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=2331-2344;drc=03c34f57c05feecfb090de3917787f049cb5f804)

When normal app directly uses `Parcel.readValue()`, [everything happens as before except a warning is printed if `length` read from `Parcel` doesn't match size of actually read data](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4330-4348;drc=03c34f57c05feecfb090de3917787f049cb5f804) (Note though that [`Slog.wtfStack` never throws](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/util/Slog.java;l=108-116;drc=23c7543b8e608ebcbb38b952761b54bb56065577))

`Bundle` however, now uses [`Parcel.readLazyValue()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4350-4420;drc=03c34f57c05feecfb090de3917787f049cb5f804) instead

Lets take closer look at how it works: in `LazyValue` class we have [nice comment explaining structure of `LazyValue` data inside `Parcel`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4392-4399;drc=03c34f57c05feecfb090de3917787f049cb5f804):

```
                     |   4B   |   4B   |
mSource = Parcel{... |  type  | length | object | ...}
                     a        b        c        d
length = d - c
mPosition = a
mLength = d - a
```

`mSource` is reference to original `Parcel` on which `readLazyValue()` was called

`mPosition` and `mLength` describe location of whole `LazyValue` data in original `Parcel`, including `type` and `length`

"`length`" (without "`m`" at beginning) refers to length value as written to `Parcel` and excludes header (`type` and `length`)

So here is what happens when someone (either system or app) takes value from `Bundle` that was read from `Parcel`:

1. Caller uses one of many `get*()` methods of `Bundle` class, for example [new `getParcelable()` with type argument](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Bundle.java;l=925-948;drc=1b74a666d3b4c6a5bf063671eb5dac62a74a9c21) (Flow will be same for both new and old methods, just new methods ensure that `clazz` argument isn't `null` while legacy ones set it to `null`)
2. [`unparcel()` is called](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=667;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91), which will check if this `Bundle` has `mParcelledData` (meaning it was read from `Parcel` but no value was accessed yet and key names were not unpacked yet, if that isn't the case skip to step 5.)
3. `unparcel()` delegates to `unparcel(boolean itemwise)`, [which calls `initializeFromParcelLocked(source, /*recycleParcel=*/ true, mParcelledByNative);`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=296;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91), `source` is set to `mParcelledData`, a copy of `Parcel` that `Bundle` has made and `recycleParcel` parameter is set to `true` to indicate that passed `Parcel` is owned by `Bundle` and it is okay to call [`Parcel.recycle()`](https://developer.android.com/reference/android/os/Parcel#recycle()) on it
4. [`initializeFromParcel` calls](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=424-425;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91) `recycleParcel &= parcelledData.readArrayMap(map, count, !parcelledByNative, /* lazy */ true, mClassLoader)` in order to read key-value map contents. Keys are `String`s and values are read using `readLazyValue()`, creating `LazyValue` objects for values of types which are written along length prefix. `readArrayMap()` returns value indicating if it is okay to recycle `Parcel`. If there were any `LazyValue` objects present `recycleParcel` is set to `false` and `Parcel` to which `LazyValue`s refer won't be recycled (there is an exception to that, but it is not relevant here, I'll describe it in "Additional note: `Bundle.clear()`" section)
5. Once `unparcel()` is done, `mMap` is set (not `null`) and maps `String` keys to either actual values if they are ready or `LazyValue` objects
6. After that, `getValue()` is called, which [maps key (`String`) to index (`int`) and passes it to `getValueAt()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=356-357;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91)
7. [`getValueAt()` detects `LazyValue` through `instanceof BiFunction` and calls `apply()` to deserialize it](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=373-377;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91)
8. [`LazyValue.apply()` rewinds `Parcel` to position of `LazyValue.mPosition` and calls normal `Parcel.readValue()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4424-4435;drc=03c34f57c05feecfb090de3917787f049cb5f804) about which I've already said
9. Upon successful deserialization [`LazyValue` is replaced in `mMap`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=386;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91), so that next `Bundle.get*()` call for same key will directly return value and `LazyValue` deserialization won't be repeated. When `Bundle` is forwarded that value will be serialized again instead of having original data copied verbatim (however after forwarded `Bundle` is read, that value will be `LazyValue` again and any possible `writeToParcel`/`createFromParcel` mismatches won't be able affect other values)

If `Bundle` is being forwarded while it still contains `LazyValue` (meaning that this particular value was not accessed, but some other value from that `Bundle` was (that is `unparcel()` was called, but `LazyValue.apply()` for&nbsp;that&nbsp;item wasn't)):

1. [`LazyValue` is detected by `Parcel.writeValue()` and write is delegated](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=2326-2330;drc=03c34f57c05feecfb090de3917787f049cb5f804) to `LazyValue.writeToParcel()`
2. [`LazyValue.writeToParcel()` uses `out.appendFrom(source, mPosition, mLength)` to copy whole `LazyValue` data from original `Parcel`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4446;drc=03c34f57c05feecfb090de3917787f049cb5f804) (again, `mPosition` and `mLength` include `LazyValue` header, so this also copies `type` and `length` from original `Parcel`)

# `Parcel.ReadWriteHelper` and `Parcel.readSquashed`

(Details of these are not important for this exploit, only relevant thing here is that these mechanisms exist)

Another interesting feature of Parcel is optional ability to deduplicate written Strings and objects

The deduplication of Strings is done by overriding [`Parcel.ReadWriteHelper` class](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=473-509;drc=03c34f57c05feecfb090de3917787f049cb5f804): `Parcel.readString()` actually [delegates to `ReadWriteHelper`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3084-3100;drc=03c34f57c05feecfb090de3917787f049cb5f804) and default helper directly [reads `String` from `Parcel`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=506-508;drc=03c34f57c05feecfb090de3917787f049cb5f804)

Alternate implementation of `Parcel.ReadWriteHelper` can replace `readString` calls with [reading pool of Strings beforehand and using `readInt` to get indexes of `String`s in pool](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/PackageParserCacheHelper.java;l=49-96;drc=23c7543b8e608ebcbb38b952761b54bb56065577); that however is never done with app-controlled `Parcel`s

`Parcel` does offer [`hasReadWriteHelper()` method](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=604-611;drc=03c34f57c05feecfb090de3917787f049cb5f804), which allows callers detect presence of such deduplication mechanism being active and disable features incompatible with it

Other deduplication mechanism available in `Parcel` is squashing:

1. First, squashing has to be enabled with [`Parcel.allowSquashing()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=2591-2612;drc=03c34f57c05feecfb090de3917787f049cb5f804)
2. Then, when class supporting squashing is being written, it first [calls `Parcel.maybeWriteSquashed(this)`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=1882-1886;drc=23c7543b8e608ebcbb38b952761b54bb56065577). If that method returned `true` it means that object was already written to this `Parcel` and now only offset to previous object data was written to `Parcel`. Otherwise (either squashing is not enabled or this is first time this object is written) `maybeWriteSquashed` writes zero as offset to indicate that object isn't squashed and returns `false` to indicate to caller that they should write actual object data
3. When reading, [`Parcel.readSquashed` is called](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=1966;drc=23c7543b8e608ebcbb38b952761b54bb56065577) and actual read function is passed to it as lambda. `readSquashed` checks if offset written by `maybeWriteSquashed()` indicates that another occurrence of object was read earlier: if yes then previously read object is returned, otherwise provided lambda is called to read it now

# Use-after-`Parcel.recycle()`

On Java side `Parcel` objects can be recycled into pool, that is once you're done with `Parcel` you call `recycle()` on it and next time someone calls `Parcel.obtain()` they'll get previously recycled `Parcel`. This allows reducing amount of object allocations and subsequent Garbage Collection

On the other hand, such manual memory management brings possibility of Use-After-Free-like bugs into Java (although with type safety, unlike usual Use-After-Free in C)

As noted above, `Bundle` creates copy of `Parcel` and won't call `Parcel.recycle()` if `LazyValue` is present, that however is not the case if `Parcel.hasReadWriteHelper()` is `true`, in that case:

1. [`initializeFromParcelLocked(parcel, /*recycleParcel=*/ false, isNativeBundle);` is called](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=1817-1823;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91), this means that `Bundle` won't recycle `Parcel` as it still belongs to caller, however this does create `LazyValue`s which refer to original `Parcel` and could outlive original `Parcel`s lifetime
2. Therefore, next thing after that is [call to `unparcel(/* itemwise */ true)`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=1824;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91), which will [use `getValueAt()` on all items](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=311-314;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91) to replace all `LazyValue`s present in `Bundle` with actual values

Now, can we make these `LazyValue`s survive step 2. and turn that behavior into Use-After-Recycle?

If deserialization fails (for example class with name specified inside `Parcel` could not be found), a [`BadParcelableException`](https://developer.android.com/reference/android/os/BadParcelableException) is thrown and then [caught by&nbsp;`getValueAt()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=378-385;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91). If `BaseBundle.sShouldDefuse` static field is `true`, an exception isn't raised and execution proceeds leaving `Bundle` containing `LazyValue` referring to original `Parcel`. `sShouldDefuse` indicates that unavailable values from `Bundle` shouldn't cause exceptions in particular process and is [set to `true` in `system_server`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/java/com/android/server/SystemServer.java;l=785-787;drc=290055a76e6ef80dd8ad7bc812d78f4fc0c5be86)

If original `Parcel` gets recycled and after that the `Bundle` read from it will be written to another `Parcel`, contents of original `Parcel` will be copied to destination `Parcel`, but at that point original `Parcel` could be reused for something else and data from unrelated IPC operation could be copied

Okay, but how do we get `Parcel.hasReadWriteHelper()` to be `true` while `Bundle` provided by us is being deserialized?

Turns out that `RemoteViews` class (normally used for example for passing [widgets](https://developer.android.com/guide/topics/appwidgets#handle-events) to home screen) [explicitly sets `ReadWriteHelper` when reading `Bundle`s nested in it](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=1865-1878;drc=03c34f57c05feecfb090de3917787f049cb5f804). This `ReadWriteHelper` doesn't do `String` deduplication and is present only to cause `Bundle` to skip copying data to secondary `Parcel`. The reason for that being done is that [`RemoteViews` enables squashing](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=6128;drc=03c34f57c05feecfb090de3917787f049cb5f804) in order to deduplicate `ApplicationInfo` objects nested in it, but this could also cause `ApplicationInfo` objects present inside `Bundle` to be squashed, so reading of that `Bundle` cannot be deferred because then those squashed objects would fail to unsquash

# Putting `Parcelable`s in `system_server` and retrieving them

So now we want `system_server` to read our `RemoteViews` containing `Bundle` containing `LazyValue` that fails to deserialize and later (in another `Binder` IPC transaction) send that object back to us

It probably could be done through some legitimate means, such as [registering ourselves as app widget host](https://developer.android.com/reference/android/appwidget/AppWidgetHost) (but that would require user interaction to grant us permission) or posting a [`Notification` with&nbsp;`contentView` set](https://developer.android.com/reference/android/app/Notification#contentView) (but that would cause interaction with other processes and/or be visible to user and I preferred to avoid both of these)

I've decided instead to create `MediaSession` and call [`setQueue(List<MediaSession.QueueItem> queue)`](https://developer.android.com/reference/android/media/session/MediaSession#setQueue(java.util.List%3Candroid.media.session.MediaSession.QueueItem%3E)) on it to send object to `system_server` and later get it back through [`List<MediaSession.QueueItem> getQueue()` method](https://developer.android.com/reference/android/media/session/MediaController#getQueue()) of `MediaController` (which can be retrieved through [`MediaSession.getController()`](https://developer.android.com/reference/android/media/session/MediaSession#getController())). While these methods don't look like they could accept `RemoteViews`, they actually do thanks to [Java Type Erasure](https://www.baeldung.com/java-type-erasure) and&nbsp;fact that under the hood they are implemented using generic serialization operations on `List`

However, I'm not using these SDK methods, I'm manually writing data for underlying `Binder` transactions (because I need to write and later read malformed serialized data), so lets take a look at how these methods work

Both of these methods had to take care of fact that total size of queue might exceed maximum size of `Binder` transaction so transfer can be split into multiple transactions

Sending "queue" to `system_server` normally goes as follows:

1. `MediaSession.setQueue()` first [calls `ISession.getBinderForSetQueue()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/media/java/android/media/session/MediaSession.java;l=534;drc=03c34f57c05feecfb090de3917787f049cb5f804)
2. On `system_server` side that methods [constructs and returns `ParcelableListBinder` object](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/media/MediaSessionRecord.java;l=1026;drc=03c34f57c05feecfb090de3917787f049cb5f804)
3. After that, `MediaSession.setQueue()` [calls `ParcelableListBinder.send()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/media/java/android/media/session/MediaSession.java;l=535;drc=03c34f57c05feecfb090de3917787f049cb5f804) which will send list contents into provided `Binder`, possibly over multiple transactions:
	* First transaction contains at beginning total number items that will be in list before contents of first part
	* Then, for [every item transferred there's `1` written and actual item is written through `Parcel.writeParcelable()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/media/java/android/media/session/ParcelableListBinder.java;l=118-122;drc=23c7543b8e608ebcbb38b952761b54bb56065577) (which writes name of class being sent and then calls `Parcelable.writeToParcel` to send data)
	* If we've approached limit of `Binder` transaction size, `0` is written to indicate that there are no more items in this transaction and next items will be sent in another transaction
4. Once `ParcelableListBinder` has received number of elements that was specified in first transaction, it invokes lambda passed to its constructor, which in this case [assigns retrieved list to `MediaSessionRecord.mQueue`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/media/MediaSessionRecord.java;l=1028;drc=03c34f57c05feecfb090de3917787f049cb5f804)

Retrieving "queue" on the other hand, goes bit differently:

1. `MediaController.getQueue()` just [calls `ISessionController.getQueue()` and unwraps received `ParceledListSlice`](https://cs.android.com/android/platform/superproject/+/android-12.1.0_r8:frameworks/base/media/java/android/media/session/MediaController.java;l=186-187)
2. On `system_server` side, `getQueue()` just [wraps `mQueue` into `ParceledListSlice` and returns it](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/media/MediaSessionRecord.java;l=1597;drc=03c34f57c05feecfb090de3917787f049cb5f804)
3. Whole split-across-multiple-transactions logic is inside `ParceledListSlice.writeToParcel()` and `createFromParcel()` methods, in particular, [`writeToParcel()` upon reaching safe size limit will write `Binder` object that allows retrieving next chunks](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/BaseParceledListSlice.java;l=175-205;drc=23c7543b8e608ebcbb38b952761b54bb56065577)
4. When `ParceledListSlice` is read from `Parcel`, it reads first part directly from `Parcel` and then [if not all elements were written inline, it calls `Binder` that was written to `Parcel` in order to retrieve these items](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/BaseParceledListSlice.java;l=82-105;drc=23c7543b8e608ebcbb38b952761b54bb56065577)

As to why these are different: there is an ongoing effort to make sure that `system_server` doesn't make outgoing synchronous `Binder` calls to other apps, because if these calls would hang that could hang whole `system_server`. This means `system_server` shouldn't be receiving `ParceledListSlice`s. While there is code that [warns about outgoing synchronous transactions from `system_server`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BinderProxy.java;l=521-539;drc=a988435f43a3431c98022cf4cff60d7b781f0211), it couldn't yet be made enforcing because there are still cases where `system_server` does make such calls, for example by [actually receiving `ParceledListSlice`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/INotificationManager.aidl;l=91-93;drc=7c9ab2d36174ff564f09cbdd322248279b3a709a)

# Choosing leak target

So now we have primitives needed to make `system_server` do `parcel_that_will_be_sent_to_us.appendFrom(some_recycled_parcel, somewhat_controlled_position, controlled_size)`

We could either randomly attempt pulling `Parcel` data from system or arrange stuff to take something specific

There are following considerations:

* When `Parcel.recycle()` is called, contents of that `Parcel` are cleared. This means that `Parcel` from which we would like to have data copied from must not be `recycle()`d, which approximately means that we can't take data from `Binder` transaction that has finished
* Alternatively, we could take data from some `Parcel` of some `Bundle` present in system (this includes `Intent` extras and `savedInstanceState` of `Activity`). These are usually not `recycle()`d at all (they are cleaned by Garbage Collector and don't return to pool, when pool gets depleted `Parcel.obtain()` creates new `Parcel` objects. Of course `Parcel`s to which we're holding reference won't be GCed, even if system has no other use for them)
* `Parcel`s used for incoming `Binder` transactions use separate pool than other `Parcel`s in system. When an outgoing `Binder` transaction is being made, `Bundle` copies data to secondary `Parcel`, or an app uses `Parcel` for their own purposes, they call [`Parcel.obtain()`, which uses `Parcel.sOwnedPool`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=513-540;drc=03c34f57c05feecfb090de3917787f049cb5f804). On the other hand, when there's an incoming `Binder` transaction, [system calls `Parcel.obtain(long obj)`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Binder.java;l=1175-1176;drc=5d7100a43bc030a42b9084dfded7f17d8e2f8286), which [uses `Parcel.sHolderPool`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=5116-5140;drc=03c34f57c05feecfb090de3917787f049cb5f804). In both cases `Parcel.recycle()` is used afterwards and takes care of [returning `Parcel` object into appropriate pool](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=574-591;drc=03c34f57c05feecfb090de3917787f049cb5f804). This means exploit must have `RemoteViews` read from `Parcel` belonging to same pool as we'd like to leak data from. Before I've decided on particular variant I've written both, so you can find both `makeOwnedLeaker` and `makeHolderLeaker` methods in my [`ValueLeakerMaker` class](app/src/main/java/com/example/leakvalue/ValueLeakerMaker.java)

In the end I've decided to attempt grabbing [`IApplicationThread`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/IApplicationThread.aidl;drc=45f4b4aaa5fd8af2d0c685b2fef8acf75ed37452) `Binder`, which is sent by app to `system_server` when app process starts and `system_server` uses it to tell application which components it should load

When application process initially starts, one of first things it does is [sending `IApplicationThread` to `system_server` through call to `attachApplication()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;l=7532;drc=95a0e9ab7dcfb2b0e1f9ddb3be6cbec558a7897b) and this is transaction from which I'll be grabbing that `Binder` from. There are other places where `IApplicationThread` is being put in `Parcel`, such as being passed for [caller identification by system when starting activity](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/IActivityManager.aidl;l=115;drc=3d7be31a284d295af4c118c5675896e6fcc28907) (but I didn't have much control over when target application does that) or being sent by system to application as [part of `Activity` lifecycle management](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/servertransaction/ClientTransaction.java;l=195;drc=3d7be31a284d295af4c118c5675896e6fcc28907) (but this is done in [oneway transaction](https://developer.android.com/reference/android/os/IBinder#FLAG_ONEWAY) outgoing from `system_server` and chances of winning race against `Parcel.recycle()` would be slim)

That being said, grabbing `Binder` that is being received by `system_server` during `attachApplication()` transaction is also nontrivial and there were few problems to overcome

# Rewinding the `Parcel`

First problem with grabbing `IApplicationThread` `Binder` from `Parcel` from&nbsp;which data for `attachApplication()` are received is that this `Binder` is&nbsp;at&nbsp;quite early/low `dataPosition()`, much lower than our `LazyValue` in `Bundle` in `RemoteViews` could be

Data for `attachApplication()` transaction consist just of [RPC header followed by `IApplicationThread` `Binder`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityManagerNative.java;l=3603-3613;drc=37ea9a1f02a1247cf0afeab987e7ca460a9eadbf). RPC header (written through [`Parcel.writeInterfaceToken()`](https://developer.android.com/reference/android/os/Parcel#writeInterfaceToken(java.lang.String))) consist of [few `int`s and name of interface](https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/binder/Parcel.cpp;l=852-869;drc=3d7be31a284d295af4c118c5675896e6fcc28907), in this case `"android.app.IActivityManager"`

Meanwhile to read `Bundle` embedded in `RemoteViews` we'd need to get past at least (few minor items are skipped):

* [Item presence flag](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/media/java/android/media/session/ParcelableListBinder.java;l=85;drc=23c7543b8e608ebcbb38b952761b54bb56065577) to start `readParcelable`
* [Name of `Parcelable`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4858;drc=03c34f57c05feecfb090de3917787f049cb5f804): `"android.view.RemoteViews"`
* Quite large [`ApplicationInfo` object present in `RemoteViews`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=3800;drc=03c34f57c05feecfb090de3917787f049cb5f804) (also it must [not be `null` and have non-`null` `packageName`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=7012;drc=03c34f57c05feecfb090de3917787f049cb5f804) or `RemoteViews.writeToParcel()` will fail when we'll try to get this object to be sent back)
* Finally we [reach `RemoteViews.readActionsFromParcel()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=3806;drc=03c34f57c05feecfb090de3917787f049cb5f804), which [calls `getActionFromParcel()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=3849;drc=03c34f57c05feecfb090de3917787f049cb5f804), which constructs [`ReflectionAction`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=3862;drc=03c34f57c05feecfb090de3917787f049cb5f804), which after [reading common `BaseReflectionAction` parameters](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=1698-1700;drc=03c34f57c05feecfb090de3917787f049cb5f804) finally [constructs `Bundle`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=1865-1878;drc=03c34f57c05feecfb090de3917787f049cb5f804)

Now in Bundle, we just need to put `String` key and reading of `LazyValue` starts, position in `Parcel` is remembered, but at this point its way past position `IApplicationThread` `Binder` would be

Can we perhaps upon reaching this point rewind position in `Parcel`? In other words could we have [`Parcel.setDataPosition()`](https://developer.android.com/reference/android/os/Parcel#setDataPosition(int)) called with value pointing to earlier position than current one?

Turn out, we can, thanks to another bug in `LazyValue`. This is code used for reading it:

```java
public Object readLazyValue(@Nullable ClassLoader loader) {
    int start = dataPosition();
    int type = readInt();
    if (isLengthPrefixed(type)) {
        int objectLength = readInt();
        int end = MathUtils.addOrThrow(dataPosition(), objectLength);
        int valueLength = end - start;
        setDataPosition(end);
        return new LazyValue(this, start, valueLength, type, loader);
    } else {
        return readValue(type, loader, /* clazz */ null);
    }
}
```

([Original in AOSP](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4376-4388;drc=03c34f57c05feecfb090de3917787f049cb5f804), `LazyValue` constructor just assigns parameters to fields)

The thing is `MathUtils.addOrThrow()` checks for overflow, [but is perfectly fine with negative values](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/util/MathUtils.java;l=276;drc=290055a76e6ef80dd8ad7bc812d78f4fc0c5be86)

If we'd try doing `Parcel.writeValue()` on `LazyValue` with negative `mLength` (filled from `valueLength` parameter) then [that would throw on `appendFrom()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4446;drc=03c34f57c05feecfb090de3917787f049cb5f804), however since we're during read of `Bundle` with `Parcel.hasReadWriteHelper()` being `true` all `LazyValue`s are unparcelled after reading and we had to intentionally put faulty `Parcelable` inside it make it stay as `LazyValue`. If we put valid parcelled data at position where `LazyValue` is, it'll be unparcelled and as noted earlier mismatched length will only trigger message in `logcat`. This particular exploit sets type to `VAL_MAP` and number of key-value pairs to zero. In `logcat` upon reading that value we can see following message: "`E Parcel  : android.util.Log$TerribleFailure: Unparcelling of {} of type VAL_MAP  consumed 4 bytes, but -540 expected.`"

(Also `LazyValue` with negative length specified can be used (without using other bugs described in this writeup) to create self-changing `Bundle`, the thing `LazyValue` was created to eliminate. But that is another story (and separately reported to Google), in this exploit I'm aiming for more)

So how much do we want to rewind?

After `setDataPosition()` call happens, reading will proceed to next key-value pair in `Bundle`, so we need to pick position where we'll have:

1. Bundle key, read using `Parcel.readString()`, can be pretty much anything, including pointing at invalid length (negative or exceeding total `Parcel` size), in that case `readString()` would return `null` which is valid key in `Bundle`
2. Value type, this must be one of [types for which `isLengthPrefixed()` returns `true`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4695-4712;drc=03c34f57c05feecfb090de3917787f049cb5f804)
3. Value length, this also shall be value controlled by us, `Parcel.appendFrom()` will fail if length is not aligned or exceeds total size of source `Parcel`

So what position in `Parcel` that could be it could be considering the same data were already read and are necessary to reach this point:

* Not before name of `Parcelable` (`"android.view.RemoteViews"`), because there's not enough space
* Not inside name of `Parcelable`, because we're unable to set type and length
* Not directly after name of `Parcelable`, because first thing in `RemoteViews` is `mode` which we [must set to `MODE_NORMAL` to reach our code](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/widget/RemoteViews.java;l=3799;drc=03c34f57c05feecfb090de3917787f049cb5f804)
* Not after that, because that's past point where `IApplicationThread` `Binder` is

Hmm, there isn't any good place when `RemoteViews` is outermost object in parcelled data

We need to find some other `Parcelable` that:

1. Has at or near beginning place where we can put arbitrary data (e.g. `int`s or `String`s that are just data and don't affect serialization process)
2. Can contain `RemoteViews` (either directly or via arbitrary `readParcelable`)
3. Has not too long fully qualified class name, because we're still size limited by position at which `IApplicationThread` stays in target `Parcel`

So I've taken list of `Parcelable` classes in system, sorted it by ascending length of fully qualified class name and began checking items on that list to see if they fulfill condition 2

That way I've reached to [`"android.os.Message"`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Message.java;drc=afdb23ab6f909c5438fa69aad458a11497cff216), which is what this exploit uses. Now process of reading item our prepared object from `Parcel` goes as follows:

* [Item presence flag](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/media/java/android/media/session/ParcelableListBinder.java;l=85;drc=23c7543b8e608ebcbb38b952761b54bb56065577) to start `readParcelable`
* [Name of `Parcelable`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=4858;drc=03c34f57c05feecfb090de3917787f049cb5f804): `"android.os.Message"`
* Few [`int`s that we can set to whatever values we want](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Message.java;l=652-656;drc=afdb23ab6f909c5438fa69aad458a11497cff216) are read into fields
* We reach `readParcelable()` call, which goes all the way we described above through `RemoteViews` and starts reading `Bundle` with `Parcel.hasReadWriteHelper` being `true`
* That `Bundle` declares to have two key-value pairs. In first value we have `LazyValue` with negative length, that triggers `Parcel.setDataPosition()` to position where `"android.os.Message"` `String` is
* Reading proceeds to second key-value pair, the key is `"android.os.Message"` and `LazyValue` type, length and data are taken from `int`s described in third bullet. I've got `LazyValue` with `mPosition` and `mLength` I wanted. Hooray!
* After `LazyValue`s are read they are unparcelled. The one with negative size gets successfully unparcelled and replaced with empty `Map`, while the other fails deserialization, but that exception is caught and&nbsp;`LazyValue` just stays in `Bundle`
* `readParcelable()` finishes, but that isn't end of `Message` data. `Message.readFromParcel()` is now continuing reading data after rewind and sees data which were initially written as part of `RemoteViews`. If&nbsp;anything throws exception at this point whole plan gets foiled
* First possible exception, [there's `readBundle()` call](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Message.java;l=660;drc=afdb23ab6f909c5438fa69aad458a11497cff216). [`Bundle` has magic value and if it is wrong an exception will be thrown](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=1809-1815;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91). That magic value however isn't present if length [is zero](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=1800-1804;drc=a9cb2102da997f016245da9a2a56f9ef134e8f91) or [negative](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Parcel.java;l=3323-3327;drc=03c34f57c05feecfb090de3917787f049cb5f804) and that happened to be the case when length of `LazyValue` data was set to value I've needed to grab `IApplicationThread`. So I just got lucky here
* Next possible problem could be [`Messenger.readMessengerOrNullFromParcel()` call](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/Message.java;l=661;drc=afdb23ab6f909c5438fa69aad458a11497cff216). This is actually wrapped `Binder` object. Reading of that `Binder` fails, because `Binder` is special object in `Parcel` and it must be annotated out-of-band to be read. This problem is [detected and logged by `Parcel` on native side, however this isn't propagated as error and `null` is simply returned](https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/binder/Parcel.cpp;l=2473-2476;drc=8b12e8333bbe17ccf4b30efb825244e1923d3a71)

# Stalling `attachApplication()`

Okay, so in previous step we've successfully created object that will allow us grabbing `IApplicationThread` object while `attachApplication()` method is running

The thing is, that method completes quickly and our chances in fair race against its completion would be rather slim

That method however, does acquire few mutexes (Through use of Java's `synchronized () {}` blocks), if we get to acquire one of such mutexes and stall there, this method would stall as well

Now lets return to few things that were already said in this writeup and will become useful for this purpose:

* `Bundle` performs deserialization of values in it when these values are accessed
* There is `ParceledListSlice` class which during deserialization will make blocking outgoing `Binder` call to object specified inside serialized data

Adding all those things together: If we find in `system_server` place where contents of `Bundle` provided by app are accessed under mutex which is also used by `attachApplication()`, we'll be able to stall `attachApplication()` until `Binder` transaction made to our process finishes

[`ActivityOptions`](https://developer.android.com/reference/android/app/ActivityOptions) is class describing various parameters related to `Activity` start (for example animation). Unlike other classes describing parameters passed to `system_server`, this one doesn't implement `Parcelable` but instead provides method for converting it to `Bundle`

On `system_server` side, that [`Bundle` is converted back to `ActivityOptions`, triggering deserialization](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityOptions.java;l=1096-1204;drc=03c34f57c05feecfb090de3917787f049cb5f804). I've found place where [that operation is being done while `ActivityTaskManagerService.mGlobalLock` mutex is held in `ActivityTaskManagerService.moveTaskToFront()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java;l=2088;drc=03c34f57c05feecfb090de3917787f049cb5f804)

So I call [`ActivityManager.moveTaskToFront()`](https://developer.android.com/reference/android/app/ActivityManager#moveTaskToFront(int,%20int,%20android.os.Bundle)), passing `Bundle` that contains `ParceledListSlice` instead of value with expected type. That [`ParceledListSlice` makes `Binder` call to my process](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/content/pm/BaseParceledListSlice.java;l=82-105;drc=23c7543b8e608ebcbb38b952761b54bb56065577) and until I return from that call the `ActivityTaskManagerService.mGlobalLock` mutex will remain locked

# Creating multiple `LazyValue`s pointing to different `Parcel`s

`Parcel.recycle()` and `Parcel.obtain()` work in [Last-In-First-Out manner](https://en.wikipedia.org/wiki/Stack_(abstract_data_type))

This means, that if I create rigged `LazyValue` when no other `Binder` transaction to `system_server` is running, I'll get `LazyValue` that will point to `Parcel` that is always used when there is only one transaction incoming to `system_server` (until it happens that two concurrent transactions incoming to `system_server` start and finish in non-stack order)

As I don't have control over what other transactions are incoming to `system_server`, in order to improve exploit reliability I've created multiple `LazyValue`s pointing to various `Parcel`s

Since I have ability to trigger synchronous `Binder` transaction to my process from `system_server`, I've used that ability to create `LazyValue` at various levels of recursion between my process and `system_server` (although this time I did so without holding a global mutex)

So:

* I create `LazyValue`
* I trigger call to `system_server`, `system_server` calls me back
    * I create `LazyValue`
    * I trigger call to `system_server`, `system_server` calls me back
        * I create `LazyValue`
        * I trigger call to `system_server`, `system_server` calls me back
            * ...

Then once I've got enough `LazyValue`s I finish doing that, return from all of these calls and all `Parcel`s which were reserved by these calls get `recycle()`d

Each of `LazyValue`s I've made is wrapped in separate [`ParceledListSlice` created by `getQueue()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/media/MediaSessionRecord.java;l=1597;drc=03c34f57c05feecfb090de3917787f049cb5f804) and I can call `ParceledListSlice` `Binder` to make `system_server` serialize it and send it to my process

(Alternative way of doing that would be creating multiple `MediaSession`s)

# Starting the target app process

Now we have everything needed to capture `IApplicationThread` from `attachApplication()` when it happens, but we still need to make `attachApplication()` happen

In general [there is a few of types of app components other app can interact with](https://developer.android.com/guide/components/fundamentals#Components), each of which requires app process to be started

I wanted to start system Settings app (which [runs under system uid](https://cs.android.com/android/platform/superproject/+/master:packages/apps/Settings/AndroidManifest.xml;l=5;drc=d131bdfbec1209548db9fbb0c63cfbaa4977ef74) and therefore has access to [everything behind Android permissions](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityManager.java;l=3948-3952;drc=16c119018a80b8630c7736a5c6dc35ebef130c5b))

Initially I've attempted to launch it through `startActivity()`, however when I've tried that process wasn't started until I've released `ActivityTaskManagerService` lock. Details on why that was case are in "Additional note: `Binder` calls and mutexes reentrancy" section, but as a solution I've decided request system for [`ContentProvider` from that app](https://cs.android.com/android/platform/superproject/+/master:packages/apps/Settings/AndroidManifest.xml;l=4031-4034;drc=d131bdfbec1209548db9fbb0c63cfbaa4977ef74) instead of `Activity`. This had additional advantage of avoiding interference with my UI

I haven't used [official `ContentResolver` API exposed by SDK](https://developer.android.com/reference/android/content/ContentResolver), but instead I've used [system internal one, as I needed asynchronous API](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/IActivityManager.aidl;l=153-154;drc=3d7be31a284d295af4c118c5675896e6fcc28907) because binding to&nbsp;`ContentProvider` wouldn't finish until `attachApplication()` which I'm hanging, although starting another thread could be alternative

(It doesn't matter what this particular `ContentProvider` offer, only relevant thing is that I can establish connection to it)

So that is how I start process of Settings app. I'm ensuring it isn't already running in first place by using [officially available `ActivityManager.killBackgroundProcesses()` method](https://developer.android.com/reference/android/app/ActivityManager#killBackgroundProcesses(java.lang.String))

# Putting it all together

Primitives are now described, so now here's how it all works together (this is pretty much transcription of `MainActivity.doAllStuff()` method from this exploit):

1. Enable hidden API access (hidden APIs are not security boundary and there are [publicly available workarounds already](https://www.xda-developers.com/bypass-hidden-apis/), although here I've used method based on [`Property.of()`](https://developer.android.com/reference/android/util/Property#of(java.lang.Class%3CT%3E,%20java.lang.Class%3CV%3E,%20java.lang.String)), which I haven't seen elsewhere)
2. (Only if we're re-running exploit after first attempt) Release connection to `ContentProvider` we established to in step 6 during previous execution. We have to do it as otherwise `ActivityManager.killBackgroundProcesses()` won't consider target process to be "background" and won't kill it
3. Kill victim application process using `ActivityManager.killBackgroundProcesses()`, as `attachApplication()` is called only at process startup
4. Request `system_server` to create bunch of objects containing `LazyValue` pointing to `Parcel` that is later recycled. I get `ParceledListSlice` `Binder` reference for each object containing `LazyValue` and I can make `Binder` transaction to it to trigger system to write it back. Each of `LazyValue` object creation is done at different depths of [mutually-recursive](https://en.wikipedia.org/wiki/Mutual_recursion) calls between `system_server` and my app to make it likely that each of these `LazyValue`s will have dangling reference to different `Parcel` object
5. I lock up `ActivityTaskManagerService.mGlobalLock` by making call to `ActivityTaskManagerService.moveTaskToFront()` passing in argument `Bundle` that upon deserialization performs synchronous `Binder` transaction to my process. Next steps are done from that callback and therefore are done with that lock held
6. I request `ActivityManagerService` for connection with `ContentProvider` of victim app (note no "`Task`" in name, `ActivityTaskManagerService` is class focused mostly on handling `Activity` components of apps, while `ActivityManagerService` handles other [app components](https://developer.android.com/guide/components/fundamentals#Components) (as well as overall process startup), this [split happened in Android 10, previously both handling of `Activity` and other app components was in `ActivityManagerService`](https://android.googlesource.com/platform/frameworks/base/+/595070969de0a7334d251d5448b641e856e052bc))
7. I `sleep()` a little bit to give newly launched process time to start calling `attachApplication()`
8. While lock is still held, I request all previously created `ParceledListSlice` objects to send their remaining contents (that didn't fit in initial transaction), that is objects containing `LazyValue` pointing to recycled `Parcel`. Then from hardcoded offset matching position of `IApplicationThread` passed to `attachApplication()` I read `Binder` object. Right now I'm only saving received `Binder`s in `ArrayList` to avoid doing too much with lock held
9. This is end of code that I do from callback started in step 5. `ActivityTaskManagerService.mGlobalLock` becomes unlocked
10. I've got `IApplicationThread` `Binder`. Now I can simply use it to load my code into victim app as described in next section

# How do I use `IApplicationThread`

As noted earlier [`IApplicationThread`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/IApplicationThread.aidl;drc=45f4b4aaa5fd8af2d0c685b2fef8acf75ed37452) `Binder`, is sent by app to `system_server` when app process starts and then `system_server` uses it to tell application which components it should load

It is assumed that this object is only passed to `system_server` and therefore there are no `Binder.getCallingUid()`-based checks there, so we can just directly call methods offered by that interface

I've [described in my previous writeup on how I get code execution by manipulating `scheduleReceiver()` arguments](https://github.com/michalbednarski/ReparcelBug2#what-then-happens-within-handlereceiver). Now situation is same except this time I'm calling `scheduleReceiver()` myself while then I was tampering with interpretation of arguments of call made by `system_server`

# Additional notes

In these section I'm describing few things that in the end didn't turn&nbsp;out to&nbsp;be&nbsp;useful in this case, although they may be features worth being aware&nbsp;of or&nbsp;are potential bugs

## Additional note: `Bundle.clear()`

For simplicity, I've here described updated `Bundle` without one [commit that was later introduced, that allows `Parcel` used in `Bundle` for backing `LazyValue`s to be recycled by calling `Bundle.clear()`](https://android.googlesource.com/platform/frameworks/base/+/1b74a666d3b4c6a5bf063671eb5dac62a74a9c21%5E%21/)

As noted in commit message, it is tracked if `Bundle` is copied and in that case `clear()` won't recycle the `Parcel`

However that commit also changes semantics of `recycleParcel` parameter/variable of [`BaseBundle.initializeFromParcelLocked()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=408-457;drc=52ae6c85e4151bb0d6c7700ae4f3a5eb697cd3c1)

Previously `recycleParcel` being `false` indicated that `Parcel` shouldn't be recycled, either because [caller set `recycleParcel` to `false` to indicate that `Parcel` isn't owned by `Bundle`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=1842-1847;drc=52ae6c85e4151bb0d6c7700ae4f3a5eb697cd3c1) or it was [set to `false` based on result of `parcelledData.readArrayMap()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/os/BaseBundle.java;l=441;drc=52ae6c85e4151bb0d6c7700ae4f3a5eb697cd3c1)

Now reasons `recycleParcel` could be `false` are same, however interpretation of that changed, now that doesn't mean "don't recycle this `Parcel`", it means "defer recycling of `Parcel` until `Bundle.clear()` call"

This means that if `clear()` would be called on `Bundle` created with `Parcel.hasReadWriteHelper()` being `true` this would led to `Parcel` being recycled, while code invoking creation of that `Bundle` would also recycle that `Parcel`, leading to double-`recycle()`, which leads to similar behavior as double-free: next calls to `Parcel.obtain()` would return same object twice

However, I haven't found way to have `clear()` called on such `Bundle`

Since I've originally written this, [behavior of `recycle()` was changed and now additional recycle is no-op with possible crash through `Log.wtf()`](https://android.googlesource.com/platform/frameworks/base/+/64ff38669a0e1f945b54c4c62ed9316282a6588d%5E%21/) ([depending on configuration, but never crashing `system_server`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=8619-8648;drc=ab23aee04d50b9bdabd52481e77265e976558056)). I'd say that new behavior still might be dangerous, especially when we have ability to programmatically stall deserialization happening in other process, but there really isn't good way to handle double-recycle

## Additional note: `Binder` calls and mutexes reentrancy

A not very well known feature of `Binder` is that it supports dispatching recursive calls to original thread

That is if process A makes synchronous `Binder` call to process B and then process B while handling it on same thread makes synchronous `Binder` call to process A, that call in process A will be dispatched in same thread that is waiting for original call to process B to complete

Other thing is that `synchronized () {}` sections in Java are reentrant mutexes, which means that if you enter it twice from same thread, it will let you in and won't deadlock

This means that in theory while we're keeping `ActivityTaskManagerService.mGlobalLock` locked, we still could start Settings app using `startActivity(new Intent(Settings.ACTION_SETTINGS))` and we'd successfully enter [`synchonized` block that we're stalling](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/wm/ActivityStarter.java;l=626;drc=8854e6eb5960c1a9b233fd0fa6e36a366b2f802d), however starting that `Activity` also involves `Task` creation, which involves calling [`notifyTaskCreated()`, which posts message](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/wm/TaskChangeNotificationController.java;l=424-429;drc=09f52aa440ab32e66dfabeb4cb40b72166930b4f) to [`DisplayThread`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/DisplayThread.java;l=25-31;drc=92b9365f9e1ea5d735e8acb06f790604036ee547), and [handling of that](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/wm/TaskChangeNotificationController.java;l=207-208;drc=09f52aa440ab32e66dfabeb4cb40b72166930b4f) attempts [acquiring lock we're stalling from another thread](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/wm/TaskChangeNotificationController.java;l=320;drc=09f52aa440ab32e66dfabeb4cb40b72166930b4f). So until release `ActivityTaskManagerService.mGlobalLock`, `DisplayThread` thread will remain blocked. Later, procedure of starting `Activity` involves [posting message to same thread in order to start app process](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java;l=4659-4664;drc=c76db81ef9d400ffed200f69c7bbda923cdb941c). All of that means that in this case app process won't be started until we release lock and&nbsp;the&nbsp;reason we were holding that lock in first place was to keep `attachApplication()` transaction from finishing so we could grab handles from it, but in this case that transaction wouldn't actually start

Even if we launch `Activity` that will be part of same `Task` as current one (that is, we'd launch different `Activity` from Settings app, one that doesn't specify `android:launchMode="singleTask"`), that procedure will still involve [`notifyTaskDescriptionChanged()`](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/wm/TaskChangeNotificationController.java;l=444-450;drc=09f52aa440ab32e66dfabeb4cb40b72166930b4f), which has same impact here as `notifyTaskCreated()`

So while my thread could call into methods which are using `synchronized (ActivityTaskManagerService.mGlobalLock) {}`, starting new app process after `startActivity()` involved use of that lock from different thread and that wasn't useful in this case, so I've opted to trigger start of app process through `ContentProvider` instead

## Additional note: Other ways to use `IApplicationThread`

`IApplicationThread` is very privileged handle, so I consider making use of it after obtaining it a post-exploitation

In this exploit I've used it directly to request code execution in target process, taking advantage of fact that access to that operation is gated by capability (possession of `Binder` object, which we here leaked) and not by `Binder.getCallingUid()`

Adding `Binder.getCallingUid()` check in [`ApplicationThread.scheduleReceiver()` (which we used here to request code execution)](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/ActivityThread.java;l=985-997;drc=09f52aa440ab32e66dfabeb4cb40b72166930b4f) and other methods of `ApplicationThread` (as `scheduleReceiver()` isn't only method in `IApplicationThread` allowing code loading) still wouldn't prevent using `IApplicationThread` to load code into process of other app, as attacker could pass leaked `IApplicationThread` in place of own one to `attachApplication()`

Besides loading code into process, having `IApplicationThread` allows performing [`grantUriPermission()` using privileges of process to which that handle belongs to](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/am/ActivityManagerService.java;l=5631-5632;drc=09f52aa440ab32e66dfabeb4cb40b72166930b4f)
