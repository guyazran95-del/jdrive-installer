package com.azran.installer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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
 *     app_process /system/bin com.azran.installer.SessionInstaller \
 *     /path/to/app.apk [/path/to/diagnostic.log]
 *
 * System.out/err are often discarded by app_process on head-unit ROMs, so
 * every milestone is also written (and flushed) to the diagnostic log file.
 */
public final class SessionInstaller {
    private static final int BUFFER_BYTES = 65536;
    private static final int INSTALL_REPLACE_EXISTING = 0x00000002;
    private static final int INSTALL_ALLOW_DOWNGRADE = 0x00000080;
    private static final int INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS = 0x00400000;
    private static final int CONTEXT_IGNORE_SECURITY = 0x00000002;
    private static final int RECEIVER_EXPORTED = 0x00000002;
    private static final int PENDING_INTENT_FLAGS = 0x08000000 | 0x02000000;
    private static final long COMMIT_TIMEOUT_SEC = 180L;
    private static final long VERSION_WAIT_MS = 15000L;
    private static final String DEFAULT_LOG = "/data/local/tmp/az-session-installer.log";

    private SessionInstaller() {}

    public static void main(String[] args) {
        String logPath = DEFAULT_LOG;
        if (args != null && args.length >= 2 && args[1] != null && args[1].length() > 0) {
            logPath = args[1];
        }

        // VERY FIRST work in main(): write "main entered" with Java I/O only.
        // Do not touch any android.* class until this line is flushed, so a later
        // linkage / class-load error still leaves a diagnostic file behind.
        FileOutputStream raw = null;
        FileLog log = null;
        int exitCode = 2;
        try {
            raw = new FileOutputStream(logPath, false);
            raw.write("main entered\n".getBytes("UTF-8"));
            raw.flush();
            log = new FileLog(raw);
        } catch (Throwable openFailed) {
            try {
                System.err.println(
                        "SessionInstaller: cannot open log " + logPath + ": " + openFailed);
            } catch (Throwable ignored) {
            }
        }

        try {
            logLine(log, "pid=" + Process.myPid()
                    + " uid=" + Process.myUid()
                    + " user=" + Process.myUserHandle());
            logLine(log, "classpath=" + System.getProperty("java.class.path"));
            logLine(log, "args.length=" + (args == null ? 0 : args.length));
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    logLine(log, "arg[" + i + "]=" + args[i]);
                }
            }

            if (args == null || args.length < 1 || args[0] == null || args[0].length() == 0) {
                logLine(log, "RESULT: USAGE — missing APK path");
                exitCode = 2;
            } else {
                installSingleApk(new File(args[0]), log);
                logLine(log, "RESULT: SUCCESS");
                System.out.println("Success");
                exitCode = 0;
            }
        } catch (Throwable t) {
            logLine(log, "RESULT: FAILED " + t.getClass().getName() + ": " + t.getMessage());
            logStack(log, t);
            try {
                System.err.println(
                        "SessionInstaller FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                t.printStackTrace(System.err);
            } catch (Throwable ignored) {
            }
            exitCode = 1;
        } finally {
            if (log != null) {
                log.close();
            } else if (raw != null) {
                try {
                    raw.flush();
                } catch (Throwable ignored) {
                }
                try {
                    raw.close();
                } catch (Throwable ignored) {
                }
            }
            System.exit(exitCode);
        }
    }

    private static void installSingleApk(File apk, FileLog log) throws Exception {
        if (!apk.isFile()) {
            throw new IllegalArgumentException("APK not found: " + apk.getAbsolutePath());
        }
        logLine(log, "apk path=" + apk.getAbsolutePath() + " size=" + apk.length());

        Context shell = createShellContext();
        logLine(log, "shell context created package=" + shell.getPackageName());

        PackageManager pm = shell.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null || archive.packageName == null) {
            throw new IllegalArgumentException("APK package metadata unavailable");
        }
        String packageName = archive.packageName;
        long versionCode = versionCodeOf(archive);
        logLine(log, "parsed packageName=" + packageName + " versionCode=" + versionCode);

        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int flagsAfter = setInstallFlags(params, log);
        params.setSize(apk.length());
        logLine(log, "session params ready installFlags=0x" + Integer.toHexString(flagsAfter));

        int sessionId = installer.createSession(params);
        logLine(log, "session created sessionId=" + sessionId);

        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try {
            long written = writeSessionFile(session, "base.apk", apk);
            logLine(log, "bytes written=" + written);

            CommitObserver observer = new CommitObserver(shell, installer, sessionId, log);
            try {
                logLine(log, "commit called");
                session.commit(observer.getIntentSender());
                committed = true;
                observer.awaitSuccess();
            } finally {
                observer.close();
            }

            boolean visible = awaitInstalledVersion(pm, packageName, versionCode, log);
            if (!visible) {
                throw new IllegalStateException(
                        "COMMIT_OK_BUT_PACKAGE_NOT_VISIBLE package="
                                + packageName
                                + " versionCode="
                                + versionCode);
            }
            logLine(log, "installed package " + packageName + " version " + versionCode);
            System.out.println("Installed package " + packageName + " version " + versionCode);
        } finally {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                    logLine(log, "session abandoned sessionId=" + sessionId);
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

    private static int setInstallFlags(PackageInstaller.SessionParams params, FileLog log)
            throws ReflectiveOperationException {
        Field field = PackageInstaller.SessionParams.class.getDeclaredField("installFlags");
        field.setAccessible(true);
        int flags = field.getInt(params);
        logLine(log, "installFlags before=0x" + Integer.toHexString(flags));
        flags |= INSTALL_REPLACE_EXISTING
                | INSTALL_ALLOW_DOWNGRADE
                | INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
        field.setInt(params, flags);
        return flags;
    }

    private static long writeSessionFile(PackageInstaller.Session session, String name, File file)
            throws IOException {
        long written = 0L;
        FileInputStream in = new FileInputStream(file);
        try {
            OutputStream out = session.openWrite(name, 0L, file.length());
            try {
                byte[] buf = new byte[BUFFER_BYTES];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    written += n;
                }
                session.fsync(out);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return written;
    }

    private static long versionCodeOf(PackageInfo info) {
        try {
            return info.getLongVersionCode();
        } catch (Throwable t) {
            return ((long) info.versionCode) & 0xffffffffL;
        }
    }

    private static boolean awaitInstalledVersion(
            PackageManager pm, String packageName, long versionCode, FileLog log)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + VERSION_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                PackageInfo installed = pm.getPackageInfo(packageName, 0);
                long got = versionCodeOf(installed);
                if (got == versionCode) {
                    logLine(log, "package visible packageName=" + packageName + " versionCode=" + got);
                    return true;
                }
                logLine(log, "package present but versionCode=" + got + " expected=" + versionCode);
            } catch (PackageManager.NameNotFoundException e) {
                // not visible yet
            }
            Thread.sleep(250L);
        }
        logLine(log, "timed out waiting for " + packageName + " version " + versionCode);
        return false;
    }

    private static String statusName(int status) {
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                return "STATUS_PENDING_USER_ACTION";
            case PackageInstaller.STATUS_SUCCESS:
                return "STATUS_SUCCESS";
            case PackageInstaller.STATUS_FAILURE:
                return "STATUS_FAILURE";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "STATUS_FAILURE_BLOCKED";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "STATUS_FAILURE_ABORTED";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "STATUS_FAILURE_INVALID";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "STATUS_FAILURE_CONFLICT";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "STATUS_FAILURE_STORAGE";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "STATUS_FAILURE_INCOMPATIBLE";
            default:
                return "UNKNOWN";
        }
    }

    private static String legacyName(int code) {
        switch (code) {
            case 1:
                return "INSTALL_SUCCEEDED";
            case -1:
                return "INSTALL_FAILED_ALREADY_EXISTS";
            case -2:
                return "INSTALL_FAILED_INVALID_APK";
            case -3:
                return "INSTALL_FAILED_INVALID_URI";
            case -4:
                return "INSTALL_FAILED_INSUFFICIENT_STORAGE";
            case -7:
                return "INSTALL_FAILED_UPDATE_INCOMPATIBLE";
            case -12:
                return "INSTALL_FAILED_OLDER_SDK";
            case -14:
                return "INSTALL_FAILED_NEWER_SDK";
            case -15:
                return "INSTALL_FAILED_TEST_ONLY";
            case -16:
                return "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE";
            case -25:
                return "INSTALL_FAILED_VERSION_DOWNGRADE";
            case -111:
                return "INSTALL_FAILED_USER_RESTRICTED";
            case -112:
                return "INSTALL_FAILED_DUPLICATE_PERMISSION";
            case -113:
                return "INSTALL_FAILED_NO_MATCHING_ABIS";
            case -115:
                return "INSTALL_FAILED_ABORTED";
            case -116:
                return "INSTALL_FAILED_SESSION_INVALID";
            default:
                return "UNKNOWN_LEGACY";
        }
    }

    private static String describeIntent(Intent intent) {
        if (intent == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("act=").append(intent.getAction());
        if (intent.getComponent() != null) {
            sb.append(" cmp=").append(intent.getComponent().flattenToShortString());
        }
        if (intent.getPackage() != null) {
            sb.append(" pkg=").append(intent.getPackage());
        }
        if (intent.getData() != null) {
            sb.append(" dat=").append(intent.getDataString());
        }
        sb.append(" flg=0x").append(Integer.toHexString(intent.getFlags()));
        Bundle extras = intent.getExtras();
        if (extras != null) {
            sb.append(" extras=[");
            boolean first = true;
            for (String key : extras.keySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                Object value = extras.get(key);
                sb.append(key).append("=");
                if (value instanceof Intent) {
                    sb.append("{").append(describeIntent((Intent) value)).append("}");
                } else {
                    sb.append(String.valueOf(value));
                }
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private static void logLine(FileLog log, String msg) {
        if (log != null) {
            log.log(msg);
        }
        try {
            System.out.println(msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logStack(FileLog log, Throwable t) {
        if (log != null) {
            log.logStack(t);
        }
    }

    private static final class FileLog {
        // Pure Java: System.currentTimeMillis, no android.* so logging keeps
        // working even if an Android class fails to link later.
        private final long startMs = System.currentTimeMillis();
        private final FileOutputStream out;

        FileLog(FileOutputStream out) {
            this.out = out;
        }

        void log(String msg) {
            long elapsed = System.currentTimeMillis() - startMs;
            writeRaw(elapsed + "ms " + msg);
        }

        void logStack(Throwable t) {
            if (t == null) {
                return;
            }
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                t.printStackTrace(pw);
                pw.flush();
                writeRaw(sw.toString());
            } catch (Throwable ignored) {
                writeRaw("(failed to stringify stack)");
            }
        }

        private synchronized void writeRaw(String text) {
            try {
                out.write(text.getBytes("UTF-8"));
                if (!text.endsWith("\n")) {
                    out.write('\n');
                }
                out.flush();
            } catch (Throwable ignored) {
            }
        }

        synchronized void close() {
            try {
                out.flush();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class FinishCallback extends PackageInstaller.SessionCallback {
        private final int sessionId;
        private final CountDownLatch latch;
        private final FileLog log;
        private volatile boolean success;
        private volatile boolean finished;

        FinishCallback(int sessionId, CountDownLatch latch, FileLog log) {
            this.sessionId = sessionId;
            this.latch = latch;
            this.log = log;
        }

        boolean wasSuccess() {
            return success;
        }

        boolean didFinish() {
            return finished;
        }

        @Override
        public void onCreated(int id) {
            if (id == sessionId) {
                logLine(log, "callback onCreated sessionId=" + id);
            }
        }

        @Override
        public void onBadgingChanged(int id) {}

        @Override
        public void onActiveChanged(int id, boolean active) {
            if (id == sessionId) {
                logLine(log, "callback onActiveChanged sessionId=" + id + " active=" + active);
            }
        }

        @Override
        public void onProgressChanged(int id, float progress) {
            if (id == sessionId) {
                logLine(log, "callback onProgressChanged sessionId=" + id + " progress=" + progress);
            }
        }

        @Override
        public void onFinished(int id, boolean ok) {
            if (id == sessionId) {
                success = ok;
                finished = true;
                logLine(log, "callback onFinished sessionId=" + id + " ok=" + ok);
                latch.countDown();
            }
        }
    }

    private static final class ResultReceiver extends BroadcastReceiver {
        private final CountDownLatch latch;
        private final FileLog log;
        private volatile boolean received;
        private volatile int status = Integer.MIN_VALUE;
        private volatile String statusMessage;
        private volatile int legacyStatus = Integer.MIN_VALUE;
        private volatile String packageName;
        private volatile String confirmDesc;
        private volatile String intentDesc;

        ResultReceiver(CountDownLatch latch, FileLog log) {
            this.latch = latch;
            this.log = log;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            received = true;
            intentDesc = describeIntent(intent);
            if (intent != null) {
                status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                legacyStatus = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", Integer.MIN_VALUE);
                packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    confirmDesc = describeIntent(confirm);
                }
            }
            logLine(log, "broadcast received");
            logLine(log, "broadcast EXTRA_STATUS=" + status + " " + statusName(status));
            logLine(log, "broadcast EXTRA_STATUS_MESSAGE=" + statusMessage);
            logLine(log, "broadcast EXTRA_LEGACY_STATUS=" + legacyStatus + " " + legacyName(legacyStatus));
            if (packageName != null) {
                logLine(log, "broadcast EXTRA_PACKAGE_NAME=" + packageName);
            }
            if (confirmDesc != null) {
                logLine(log, "broadcast confirmIntent=" + confirmDesc);
            }
            logLine(log, "broadcast intent=" + intentDesc);
            latch.countDown();
        }
    }

    private static final class CommitObserver {
        private final Context context;
        private final PackageInstaller installer;
        private final PendingIntent pendingIntent;
        private final FinishCallback callback;
        private final ResultReceiver receiver;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final HandlerThread handlerThread = new HandlerThread("az-session-commit");
        private final FileLog log;
        private boolean receiverRegistered;

        CommitObserver(Context context, PackageInstaller installer, int sessionId, FileLog log) {
            this.context = context;
            this.installer = installer;
            this.log = log;
            this.handlerThread.start();
            this.callback = new FinishCallback(sessionId, latch, log);
            this.receiver = new ResultReceiver(latch, log);
            installer.registerSessionCallback(callback, new Handler(handlerThread.getLooper()));

            String action = "com.azran.installer.SESSION_RESULT." + sessionId;
            registerReceiver(action);
            Intent intent = new Intent(action).setPackage("com.android.shell");
            this.pendingIntent =
                    PendingIntent.getBroadcast(context, sessionId, intent, PENDING_INTENT_FLAGS);
            logLine(log, "CommitObserver ready action=" + action);
        }

        private void registerReceiver(String action) {
            IntentFilter filter = new IntentFilter(action);
            Handler handler = new Handler(handlerThread.getLooper());
            try {
                context.registerReceiver(receiver, filter, null, handler, RECEIVER_EXPORTED);
                receiverRegistered = true;
                logLine(log, "BroadcastReceiver registered (exported) action=" + action);
            } catch (Throwable first) {
                try {
                    context.registerReceiver(receiver, filter, null, handler);
                    receiverRegistered = true;
                    logLine(log, "BroadcastReceiver registered (legacy) action=" + action);
                } catch (Throwable second) {
                    logLine(log, "BroadcastReceiver register failed: " + second);
                    logStack(log, second);
                }
            }
        }

        IntentSender getIntentSender() {
            return pendingIntent.getIntentSender();
        }

        void awaitSuccess() throws Exception {
            boolean arrived = latch.await(COMMIT_TIMEOUT_SEC, TimeUnit.SECONDS);
            // Let the other signal (callback vs broadcast) arrive if it is close behind.
            try {
                Thread.sleep(400L);
            } catch (InterruptedException ignored) {
            }

            logLine(log, "await done arrived=" + arrived
                    + " broadcast=" + receiver.received
                    + " callback=" + callback.didFinish()
                    + " callbackOk=" + callback.wasSuccess()
                    + " status=" + receiver.status + " " + statusName(receiver.status)
                    + " message=" + receiver.statusMessage
                    + " legacy=" + receiver.legacyStatus + " " + legacyName(receiver.legacyStatus));

            if (!arrived) {
                throw new IllegalStateException(
                        "Timed out waiting for package commit result"
                                + " (no broadcast, no SessionCallback.onFinished)");
            }

            if (receiver.received) {
                if (receiver.status == PackageInstaller.STATUS_SUCCESS) {
                    return;
                }
                if (receiver.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    throw new IllegalStateException(
                            "STATUS_PENDING_USER_ACTION — install needs confirmation on the car screen"
                                    + " confirm=" + receiver.confirmDesc);
                }
                throw new IllegalStateException(
                        "Package commit failed status="
                                + receiver.status
                                + " "
                                + statusName(receiver.status)
                                + " message="
                                + receiver.statusMessage
                                + " legacy="
                                + receiver.legacyStatus
                                + " "
                                + legacyName(receiver.legacyStatus));
            }

            if (!callback.wasSuccess()) {
                throw new IllegalStateException(
                        "Package commit failed (SessionCallback.onFinished ok=false; no result broadcast)");
            }
            logLine(log, "no result broadcast; treating SessionCallback.onFinished ok=true as success");
        }

        void close() {
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Throwable ignored) {
                }
                receiverRegistered = false;
            }
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
