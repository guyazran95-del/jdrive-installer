package com.azran.installer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal PackageInstaller session helper for ADB shell (app_process).
 *
 * Bypasses PackageManagerShellCommand.doCreateSession DISALLOW_INSTALL_APPS
 * Restriction by using a com.android.shell context and setting installFlags
 * via reflection (REPLACE_EXISTING | ALLOW_DOWNGRADE |
 * ALL_WHITELIST_RESTRICTED_PERMISSIONS).
 *
 * Usage:
 *   CLASSPATH=/data/local/tmp/session-installer.dex \
 *     app_process /system/bin com.azran.installer.SessionInstaller /path/to/app.apk
 */
public final class SessionInstaller {
    private static final int BUFFER_BYTES = 65536;
    private static final int INSTALL_REPLACE_EXISTING = 0x00000002;
    private static final int INSTALL_ALLOW_DOWNGRADE = 0x00000080;
    private static final int INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS = 0x00400000;
    private static final int CONTEXT_IGNORE_SECURITY = 0x00000002;
    private static final int PENDING_INTENT_FLAGS = 0x08000000 | 0x02000000;
    private static final long COMMIT_TIMEOUT_SEC = 180L;
    private static final long VERSION_WAIT_MS = 15000L;

    private SessionInstaller() {}

    public static void main(String[] args) throws Exception {
        if (args == null || args.length != 1 || args[0] == null || args[0].length() == 0) {
            System.err.println("usage: SessionInstaller /data/local/tmp/app.apk");
            System.exit(2);
            return;
        }
        try {
            installSingleApk(new File(args[0]));
            System.exit(0);
        } catch (Throwable t) {
            System.err.println(
                    "SessionInstaller FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void installSingleApk(File apk) throws Exception {
        if (!apk.isFile()) {
            throw new IllegalArgumentException("APK not found: " + apk.getAbsolutePath());
        }
        Context shell = createShellContext();
        PackageManager pm = shell.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null || archive.packageName == null) {
            throw new IllegalArgumentException("APK package metadata unavailable");
        }
        String packageName = archive.packageName;
        long versionCode = versionCodeOf(archive);

        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        setInstallFlags(params);
        params.setSize(apk.length());

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try {
            writeSessionFile(session, "base.apk", apk);
            CommitObserver observer = new CommitObserver(shell, installer, sessionId);
            try {
                session.commit(observer.getIntentSender());
                committed = true;
                observer.awaitSuccess();
            } finally {
                observer.close();
            }
            awaitInstalledVersion(pm, packageName, versionCode);
            System.out.println("Success");
            System.out.println("Installed package " + packageName + " version " + versionCode);
        } finally {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static Context createShellContext() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getDeclaredMethod("systemMain").invoke(null);
        Context system = (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
        return system.createPackageContext("com.android.shell", CONTEXT_IGNORE_SECURITY);
    }

    private static void setInstallFlags(PackageInstaller.SessionParams params)
            throws ReflectiveOperationException {
        Field field = PackageInstaller.SessionParams.class.getDeclaredField("installFlags");
        field.setAccessible(true);
        int flags = field.getInt(params);
        flags |= INSTALL_REPLACE_EXISTING
                | INSTALL_ALLOW_DOWNGRADE
                | INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
        field.setInt(params, flags);
    }

    private static void writeSessionFile(PackageInstaller.Session session, String name, File file)
            throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            OutputStream out = session.openWrite(name, 0L, file.length());
            try {
                byte[] buf = new byte[BUFFER_BYTES];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static long versionCodeOf(PackageInfo info) {
        try {
            return info.getLongVersionCode();
        } catch (Throwable t) {
            return ((long) info.versionCode) & 0xffffffffL;
        }
    }

    private static void awaitInstalledVersion(PackageManager pm, String packageName, long versionCode)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + VERSION_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                PackageInfo installed = pm.getPackageInfo(packageName, 0);
                if (versionCodeOf(installed) == versionCode) {
                    return;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // not visible yet
            }
            Thread.sleep(250L);
        }
        System.out.println(
                "Warning: timed out waiting for " + packageName + " version " + versionCode);
    }

    private static final class FinishCallback extends PackageInstaller.SessionCallback {
        private final int sessionId;
        private final CountDownLatch latch;
        private volatile boolean success;

        FinishCallback(int sessionId, CountDownLatch latch) {
            this.sessionId = sessionId;
            this.latch = latch;
        }

        boolean wasSuccess() {
            return success;
        }

        @Override
        public void onCreated(int id) {}

        @Override
        public void onBadgingChanged(int id) {}

        @Override
        public void onActiveChanged(int id, boolean active) {}

        @Override
        public void onProgressChanged(int id, float progress) {}

        @Override
        public void onFinished(int id, boolean ok) {
            if (id == sessionId) {
                success = ok;
                latch.countDown();
            }
        }
    }

    private static final class CommitObserver {
        private final PackageInstaller installer;
        private final PendingIntent pendingIntent;
        private final FinishCallback callback;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final HandlerThread handlerThread = new HandlerThread("az-session-commit");

        CommitObserver(Context context, PackageInstaller installer, int sessionId) {
            this.installer = installer;
            this.handlerThread.start();
            this.callback = new FinishCallback(sessionId, latch);
            installer.registerSessionCallback(callback, new Handler(handlerThread.getLooper()));
            Intent intent =
                    new Intent("com.azran.installer.SESSION_RESULT." + sessionId)
                            .setPackage("com.android.shell");
            this.pendingIntent =
                    PendingIntent.getBroadcast(context, sessionId, intent, PENDING_INTENT_FLAGS);
        }

        IntentSender getIntentSender() {
            return pendingIntent.getIntentSender();
        }

        void awaitSuccess() throws Exception {
            if (!latch.await(COMMIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for package commit result");
            }
            if (!callback.wasSuccess()) {
                throw new IllegalStateException("Package commit failed");
            }
        }

        void close() {
            try {
                pendingIntent.cancel();
            } catch (Throwable ignored) {
            }
            try {
                installer.unregisterSessionCallback(callback);
            } catch (Throwable ignored) {
            }
            handlerThread.quitSafely();
        }
    }
}
