# JDrive installer sources (snapshot)

Working tree for development: `/workspace/az-installer` on the agent box.
This folder is a deploy-time snapshot of the pieces that implement the
PackageInstaller session bypass.

Rebuild DEX:
```
javac --release 8 -classpath $ANDROID_JAR -d out native/.../SessionInstaller.java
d8 --release --min-api 26 --lib $ANDROID_JAR --output out.dex out.jar
```
