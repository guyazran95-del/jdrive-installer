# JDrive installer sources (snapshot)

Working tree for development: `/workspace/az-installer` on the agent box.
This folder is a deploy-time snapshot of the pieces that implement the
PackageInstaller session bypass.

Rebuild DEX:
```
ANDROID_JAR=/home/box/android-sdk/platforms/android-34/android.jar
D8=/home/box/android-sdk/build-tools/34.0.0/d8
javac --release 8 -classpath $ANDROID_JAR -d out native/com/azran/installer/SessionInstaller.java
jar cf out.jar -C out .
$D8 --release --min-api 26 --lib $ANDROID_JAR --output out.dex-dir out.jar
```
