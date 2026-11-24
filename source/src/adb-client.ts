import { Adb, AdbDaemonTransport } from "@yume-chan/adb";
import AdbWebCredentialStore from "@yume-chan/adb-credential-web";
import {
  AdbDaemonWebUsbDevice,
  AdbDaemonWebUsbDeviceManager,
} from "@yume-chan/adb-daemon-webusb";
import type { ReadableStream as TangoReadableStream } from "@yume-chan/stream-extra";
import {
  InstallerError,
  errorMessage,
  isUserCancelledPicker,
  looksLikeAuthFailure,
  looksLikeDisconnect,
} from "./errors";

/** Shown on the car ADB authorization dialog (Android 11+ paired devices). */
const CREDENTIAL_NAME = "AZDrive";

const credentialStore = new AdbWebCredentialStore(CREDENTIAL_NAME);

export type WebUsbSupport = "ok" | "insecure" | "unsupported";

export interface DeviceInfo {
  usbName: string;
  serial: string;
  model?: string;
  manufacturer?: string;
  product?: string;
  buildId?: string;
}

export interface CarSession {
  adb: Adb;
  device: AdbDaemonWebUsbDevice;
  info: DeviceInfo;
  close(): Promise<void>;
}

export type StatusFn = (message: string) => void;
export type ProgressFn = (sent: number, total: number) => void;

export interface InstallResult {
  output: string;
  remotePath: string;
  success: boolean;
}

export function getWebUsbSupport(): WebUsbSupport {
  if (AdbDaemonWebUsbDeviceManager.BROWSER) return "ok";
  if (typeof window !== "undefined" && !window.isSecureContext) return "insecure";
  return "unsupported";
}

function withProgress(
  file: File,
  onProgress?: ProgressFn,
): TangoReadableStream<Uint8Array> {
  let sent = 0;
  const total = file.size;
  const transform = new TransformStream<Uint8Array, Uint8Array>({
    transform(chunk, controller) {
      sent += chunk.byteLength;
      onProgress?.(Math.min(sent, total), total);
      controller.enqueue(chunk);
    },
    flush() {
      onProgress?.(total, total);
    },
  });
  // DOM ReadableStream typings are not identical to Tango's stream typings.
  return file.stream().pipeThrough(transform) as unknown as TangoReadableStream<Uint8Array>;
}

export async function looksLikeApk(file: File): Promise<boolean> {
  if (file.size < 4) return false;
  const head = new Uint8Array(await file.slice(0, 4).arrayBuffer());
  // APK is a ZIP: PK\x03\x04, PK\x05\x06 (empty), or PK\x07\x08
  return head[0] === 0x50 && head[1] === 0x4b;
}

export function safeApkFileName(original: string): string {
  const base = original.replace(/^.*[/\\]/, "");
  const sanitized =
    base.replace(/[^\w.-]+/g, "_").replace(/^\.+/, "") || "app.apk";
  const withExt = /\.apk$/i.test(sanitized) ? sanitized : `${sanitized}.apk`;
  if (withExt.length <= 80) return withExt;
  return `${withExt.slice(0, 76)}.apk`;
}

export function isPmSuccess(output: string): boolean {
  return /success/i.test(output);
}

async function readProp(adb: Adb, key: string): Promise<string | undefined> {
  try {
    const value = (await adb.getProp(key)).trim();
    return value.length > 0 ? value : undefined;
  } catch {
    return undefined;
  }
}

async function readDeviceInfo(
  adb: Adb,
  device: AdbDaemonWebUsbDevice,
): Promise<DeviceInfo> {
  const [model, manufacturer, serialProp, buildId] = await Promise.all([
    readProp(adb, "ro.product.model"),
    readProp(adb, "ro.product.manufacturer"),
    readProp(adb, "ro.serialno"),
    readProp(adb, "ro.build.display.id"),
  ]);

  return {
    usbName: device.name || device.raw.productName || "Android",
    serial: serialProp || device.serial || adb.serial,
    model: model || adb.banner.model,
    manufacturer,
    product: adb.banner.product,
    buildId,
  };
}

export async function connectToCar(onStatus?: StatusFn): Promise<CarSession> {
  const support = getWebUsbSupport();
  if (support === "insecure") {
    throw new InstallerError(
      "insecure-context",
      "WebUSB works only on HTTPS or localhost. Open this page in Chrome or Edge at http://localhost or over HTTPS.",
    );
  }
  if (support !== "ok") {
    throw new InstallerError(
      "webusb-unsupported",
      "WebUSB is not available. Use Chrome or Edge (not Firefox or Safari).",
    );
  }

  const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
  if (!manager) {
    throw new InstallerError(
      "webusb-unsupported",
      "WebUSB is not available in this browser.",
    );
  }

  onStatus?.("Select the car in the browser device picker…");
  let device: AdbDaemonWebUsbDevice | undefined;
  try {
    device = await manager.requestDevice();
  } catch (error) {
    if (isUserCancelledPicker(error)) {
      throw new InstallerError(
        "no-device",
        "No device was selected. Connect the A-to-A cable and try again.",
      );
    }
    throw error;
  }
  if (!device) {
    throw new InstallerError(
      "no-device",
      "No device was selected. Connect the A-to-A cable and try again.",
    );
  }

  onStatus?.("Opening the USB connection…");
  let connection: Awaited<ReturnType<AdbDaemonWebUsbDevice["connect"]>>;
  try {
    connection = await device.connect();
  } catch (error) {
    if (error instanceof AdbDaemonWebUsbDevice.DeviceBusyError) {
      throw new InstallerError(
        "device-busy",
        "The USB interface is already claimed by another program. Quit Android Studio and run adb kill-server, then try again.",
        errorMessage(error),
      );
    }
    if (looksLikeDisconnect(error)) {
      throw new InstallerError(
        "disconnected",
        "The USB connection failed. Check the A-to-A cable and the car USB port, then try again.",
        errorMessage(error),
      );
    }
    throw error;
  }

  onStatus?.(
    "Waiting for authorization on the car screen. Tap Allow (and preferably Always allow)…",
  );
  let adb: Adb;
  try {
    const transport = await AdbDaemonTransport.authenticate({
      serial: device.serial,
      connection,
      credentialStore,
    });
    adb = new Adb(transport);
  } catch (error) {
    try {
      await connection.readable.cancel();
      await connection.writable.close();
    } catch {
      // Connection may already be closed after a failed handshake.
    }
    if (looksLikeAuthFailure(error) || looksLikeDisconnect(error)) {
      throw new InstallerError(
        "auth-rejected",
        "Authorization failed or was declined. Accept the ADB dialog on the car screen and connect again.",
        errorMessage(error),
      );
    }
    throw new InstallerError(
      "auth-rejected",
      "Could not authenticate with the head unit. Accept the ADB dialog on the car screen and connect again.",
      errorMessage(error),
    );
  }

  onStatus?.("Reading device information…");
  const info = await readDeviceInfo(adb, device);

  let closed = false;
  const session: CarSession = {
    adb,
    device,
    info,
    async close() {
      if (closed) return;
      closed = true;
      try {
        await adb.close();
      } catch {
        // Already disconnected is fine.
      }
    },
  };

  void adb.disconnected.then(() => {
    closed = true;
  });

  return session;
}

export async function installApk(
  session: CarSession,
  file: File,
  options?: { onStatus?: StatusFn; onProgress?: ProgressFn },
): Promise<InstallResult> {
  if (!file.name.toLowerCase().endsWith(".apk")) {
    throw new InstallerError(
      "invalid-apk",
      "Please choose a .apk file.",
    );
  }
  if (!(await looksLikeApk(file))) {
    throw new InstallerError(
      "invalid-apk",
      "The selected file does not look like a valid APK (missing ZIP header).",
    );
  }

  const stamp = Date.now();
  const remoteName = `azdrive-${stamp}-${safeApkFileName(file.name)}`;
  const remotePath = `/data/local/tmp/${remoteName}`;
  const remoteDex = `/data/local/tmp/az-session-installer-${stamp}.dex`;

  // 1) Push APK
  const sync = await session.adb.sync();
  try {
    options?.onStatus?.("Pushing the APK to /data/local/tmp…");
    options?.onProgress?.(0, file.size);
    await sync.write({
      filename: remotePath,
      file: withProgress(file, options?.onProgress),
      permission: 0o644,
    });
  } catch (error) {
    try {
      await session.adb.rm(remotePath, { force: true });
    } catch {
      // Best-effort cleanup of a partial push.
    }
    if (looksLikeDisconnect(error)) {
      throw new InstallerError(
        "disconnected",
        "The USB connection dropped while pushing the APK. Check the cable and reconnect.",
        errorMessage(error),
      );
    }
    throw new InstallerError(
      "push-failed",
      "Failed to push the APK to the head unit.",
      errorMessage(error),
    );
  } finally {
    await sync.dispose();
  }

  let output = "";
  try {
    // Best-effort clear of DISALLOW_INSTALL_APPS — helps fallback; SessionInstaller
    // does not rely on it.
    try {
      await clearInstallRestrictions(session, options?.onStatus);
    } catch (error) {
      if (looksLikeDisconnect(error)) throw error;
    }

    // 2) Primary path: PackageInstaller session via our DEX + app_process
    //    (same technique family as JConfig/JCarTools/DSA — not pm install).
    options?.onStatus?.("Pushing the session installer helper…");
    try {
      await pushSessionInstallerDex(session, remoteDex);
    } catch (error) {
      if (looksLikeDisconnect(error)) {
        throw new InstallerError(
          "disconnected",
          "The USB connection dropped while pushing the installer helper. Check the cable and reconnect.",
          errorMessage(error),
        );
      }
      throw new InstallerError(
        "push-failed",
        "Failed to push the session installer helper to the head unit.",
        errorMessage(error),
      );
    }

    options?.onStatus?.("Installing via PackageInstaller session (app_process)…");
    try {
      output = await runSessionInstaller(session, remoteDex, remotePath);
    } catch (error) {
      if (looksLikeDisconnect(error)) {
        throw new InstallerError(
          "disconnected",
          "The USB connection dropped during session install. Check the cable and reconnect.",
          errorMessage(error),
        );
      }
      // Fall through to pm install with the error text preserved.
      output = errorMessage(error);
    }

    if (isPmSuccess(output)) {
      return { output: output.trim() || "Success", remotePath, success: true };
    }

    // 3) Last-resort fallback: pm install -r -d -g (FrostNav/JConfig docs style).
    //    Still hits Restriction on locked units — kept for unlocked / engmode cars.
    options?.onStatus?.(
      "Session install did not report Success; falling back to pm install -r -d -g…",
    );
    try {
      output = await shellText(session, [
        "pm",
        "install",
        "-r",
        "-d",
        "-g",
        remotePath,
      ]);
    } catch (error) {
      if (looksLikeDisconnect(error)) {
        throw new InstallerError(
          "disconnected",
          "The USB connection dropped during pm install. Check the cable and reconnect.",
          errorMessage(error),
        );
      }
      output = errorMessage(error);
    }

    if (!isPmSuccess(output) && isInstallRestriction(output)) {
      options?.onStatus?.(
        "Install blocked by user restriction. Clearing no_install_apps and retrying pm…",
      );
      await clearInstallRestrictions(session, options?.onStatus);
      const userIds = await listInstallUserIds(session, options?.onStatus);
      for (const userId of userIds) {
        options?.onStatus?.(
          `Retrying pm install -r -d -g --user ${userId}…`,
        );
        try {
          output = await shellText(session, [
            "pm",
            "install",
            "-r",
            "-d",
            "-g",
            "--user",
            userId,
            remotePath,
          ]);
        } catch (error) {
          if (looksLikeDisconnect(error)) {
            throw new InstallerError(
              "disconnected",
              "The USB connection dropped during pm install. Check the cable and reconnect.",
              errorMessage(error),
            );
          }
          options?.onStatus?.(
            `pm install --user ${userId} failed to run; trying next user…`,
          );
          continue;
        }
        if (isPmSuccess(output)) break;
      }
    }
  } finally {
    options?.onStatus?.("Removing temporary install files…");
    for (const p of [remotePath, remoteDex]) {
      try {
        await session.adb.rm(p, { force: true });
      } catch {
        // Installation result matters more than temp cleanup.
      }
    }
  }

  const success = isPmSuccess(output);
  if (!success) {
    const detail = output.trim() || "Install produced no output.";
    const restrictionHint = isInstallRestriction(detail)
      ? " Restriction still blocks shell pm install; session installer should be used (and may have failed earlier — see detail)."
      : "";
    throw new InstallerError(
      "install-failed",
      "The head unit rejected the installation." + restrictionHint,
      detail,
    );
  }

  return { output: output.trim() || "Success", remotePath, success };
}

const SESSION_INSTALLER_CLASS = "com.azran.installer.SessionInstaller";
const SESSION_DEX_ASSET = "session-installer.dex";

/** Cached DEX bytes fetched once from the static Pages asset. */
let sessionDexBytes: Uint8Array | undefined;

async function loadSessionInstallerDex(): Promise<Uint8Array> {
  if (sessionDexBytes) return sessionDexBytes;
  const url = new URL(SESSION_DEX_ASSET, document.baseURI).href;
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Failed to fetch ${SESSION_DEX_ASSET}: HTTP ${res.status}`);
  }
  sessionDexBytes = new Uint8Array(await res.arrayBuffer());
  if (sessionDexBytes.byteLength < 16) {
    throw new Error(`${SESSION_DEX_ASSET} looks empty or corrupt`);
  }
  return sessionDexBytes;
}

function bytesToStream(bytes: Uint8Array): TangoReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  }) as unknown as TangoReadableStream<Uint8Array>;
}

async function pushSessionInstallerDex(
  session: CarSession,
  remoteDex: string,
): Promise<void> {
  const bytes = await loadSessionInstallerDex();
  const sync = await session.adb.sync();
  try {
    await sync.write({
      filename: remoteDex,
      file: bytesToStream(bytes),
      permission: 0o644,
    });
  } finally {
    await sync.dispose();
  }
}

async function runSessionInstaller(
  session: CarSession,
  remoteDex: string,
  remoteApk: string,
): Promise<string> {
  // Escape for embedding in a single-quoted sh -c string.
  const q = (s: string) => s.replace(/'/g, "'\\''");
  const cmd =
    `CLASSPATH='${q(remoteDex)}' app_process /system/bin ${SESSION_INSTALLER_CLASS} '${q(remoteApk)}'`;
  return shellText(session, ["sh", "-c", cmd]);
}

function isInstallRestriction(text: string): boolean {
  return /restriction prevents installing/i.test(text);
}

async function shellText(
  session: CarSession,
  argv: string[],
): Promise<string> {
  try {
    return await session.adb.subprocess.noneProtocol.spawnWaitText(argv);
  } catch (error) {
    if (looksLikeDisconnect(error)) throw error;
    return errorMessage(error);
  }
}

async function clearInstallRestrictions(
  session: CarSession,
  onStatus?: StatusFn,
): Promise<void> {
  const attempts: string[][] = [
    ["pm", "set-user-restriction", "no_install_apps", "false"],
    ["cmd", "user", "clear-restriction", "0", "no_install_apps"],
    ["cmd", "user", "clear-restriction", "no_install_apps"],
    [
      "settings",
      "put",
      "global",
      "verifier_verify_adb_installs",
      "0",
    ],
  ];

  for (const argv of attempts) {
    onStatus?.(`Trying: ${argv.join(" ")}`);
    try {
      await shellText(session, argv);
    } catch (error) {
      if (looksLikeDisconnect(error)) throw error;
      // Best-effort; keep going.
    }
  }

  // Also clear for every listed user id we can see.
  const userIds = await listInstallUserIds(session, onStatus);
  for (const userId of userIds) {
    try {
      await shellText(session, [
        "pm",
        "set-user-restriction",
        "--user",
        userId,
        "no_install_apps",
        "false",
      ]);
    } catch (error) {
      if (looksLikeDisconnect(error)) throw error;
    }
    try {
      await shellText(session, [
        "cmd",
        "user",
        "clear-restriction",
        userId,
        "no_install_apps",
      ]);
    } catch (error) {
      if (looksLikeDisconnect(error)) throw error;
    }
  }
}

async function listInstallUserIds(
  session: CarSession,
  onStatus?: StatusFn,
): Promise<string[]> {
  onStatus?.("Listing users (pm list users)…");
  let listing = "";
  try {
    listing = await shellText(session, ["pm", "list", "users"]);
  } catch (error) {
    if (looksLikeDisconnect(error)) throw error;
    return ["0"];
  }

  const ids = new Set<string>(["0"]);
  // Typical: UserInfo{0:Owner:13} running / UserInfo{10:Driver:..}
  for (const match of listing.matchAll(/UserInfo\{(\d+):([^:}]+)/g)) {
    const id = match[1];
    const name = (match[2] || "").toLowerCase();
    if (/guest|restricted/.test(name)) continue;
    ids.add(id);
  }
  // Prefer 0 first, then higher ids (head-unit UI often lives on 10).
  return Array.from(ids).sort((a, b) => Number(a) - Number(b));
}
