#!/bin/sh
set -xe
javac android/content/pm/PackageManagerInternal.java
javac com/example/MockPackageManager.java
dx --dex --output=../app/src/main/res/raw/mock_system.dex com/example/MockPackageManager.class
