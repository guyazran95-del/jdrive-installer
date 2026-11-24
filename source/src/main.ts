import {
  connectToCar,
  getWebUsbSupport,
  installApk,
  type CarSession,
} from "./adb-client";
import { InstallerError, errorMessage } from "./errors";

const connectBtn = document.querySelector("#connect-btn") as HTMLButtonElement;
const disconnectBtn = document.querySelector("#disconnect-btn") as HTMLButtonElement;
const connectStatus = document.querySelector("#connect-status") as HTMLParagraphElement;
const deviceInfo = document.querySelector("#device-info") as HTMLDivElement;
const deviceFields = document.querySelector("#device-fields") as HTMLDListElement;
const installCard = document.querySelector("#install-card") as HTMLElement;
const apkInput = document.querySelector("#apk-input") as HTMLInputElement;
const fileLabel = document.querySelector("#file-label") as HTMLSpanElement;
const fileMeta = document.querySelector("#file-meta") as HTMLParagraphElement;
const installBtn = document.querySelector("#install-btn") as HTMLButtonElement;
const progressWrap = document.querySelector("#progress-wrap") as HTMLDivElement;
const progressFill = document.querySelector("#progress-fill") as HTMLDivElement;
const progressText = document.querySelector("#progress-text") as HTMLParagraphElement;
const installStatus = document.querySelector("#install-status") as HTMLParagraphElement;
const installOutput = document.querySelector("#install-output") as HTMLPreElement;
const browserBanner = document.querySelector("#browser-banner") as HTMLDivElement;

let session: CarSession | undefined;
let selectedFile: File | undefined;
let connecting = false;
let installing = false;

const hebrew: Record<string, string> = {
  "insecure-context":
    "WebUSB עובד רק ב־HTTPS או ב־localhost. פתחו את האתר ב־Chrome או Edge בכתובת localhost או באתר מאובטח.",
  "webusb-unsupported":
    "הדפדפן לא תומך ב־WebUSB. יש להשתמש ב־Chrome או Edge (לא Firefox ולא Safari).",
  "no-device":
    "לא נבחר מכשיר. חברו את כבל ה־A-to-A, בחרו את הרכב בחלון של הדפדפן, ונסו שוב.",
  "device-busy":
    "יציאת ה־USB תפוסה על ידי תוכנה אחרת. סגרו את Android Studio והריצו adb kill-server, ואז נסו שוב.",
  "auth-rejected":
    "האישור נדחה או נכשל. אשרו את דיאלוג ה־ADB במסך הרכב (מומלץ לסמן Always allow) ונסו שוב.",
  disconnected: "החיבור ב־USB נותק. בדקו את הכבל ואת יציאת הרכב, ואז התחברו מחדש.",
  "invalid-apk": "יש לבחור קובץ APK תקין.",
  "push-failed": "העברת ה־APK לרכב נכשלה.",
  "install-failed": "ההתקנה נדחתה על ידי הרכב.",
  unknown: "אירעה שגיאה לא צפויה.",
};

function setStatus(el: HTMLElement, message: string, kind: "ok" | "warn" | "err" | "" = "") {
  el.textContent = message;
  el.classList.remove("ok", "warn", "err");
  if (kind) el.classList.add(kind);
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}

function friendlyError(error: unknown): { text: string; detail?: string } {
  if (error instanceof InstallerError) {
    return {
      text: hebrew[error.code] ?? error.message,
      detail: error.detail,
    };
  }
  return { text: hebrew.unknown, detail: errorMessage(error) };
}

function showOutput(text: string | undefined) {
  if (!text) {
    installOutput.hidden = true;
    installOutput.textContent = "";
    return;
  }
  installOutput.hidden = false;
  installOutput.textContent = text;
}

function setInstallEnabled() {
  const ready = Boolean(session) && Boolean(selectedFile) && !installing && !connecting;
  installBtn.disabled = !ready;
  installCard.classList.toggle("is-disabled", !session);
  apkInput.disabled = !session || installing;
}

function renderDevice(current: CarSession) {
  const rows: Array<[string, string | undefined]> = [
    ["שם USB", current.info.usbName],
    ["דגם", current.info.model],
    ["יצרן", current.info.manufacturer],
    ["מספר סידורי", current.info.serial],
    ["מוצר", current.info.product],
    ["גרסת מערכת", current.info.buildId],
  ];
  deviceFields.replaceChildren();
  for (const [label, value] of rows) {
    if (!value) continue;
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    deviceFields.append(dt, dd);
  }
  deviceInfo.hidden = false;
}

function clearDevice() {
  deviceInfo.hidden = true;
  deviceFields.replaceChildren();
}

function resetProgress() {
  progressWrap.hidden = true;
  progressFill.style.width = "0%";
  progressText.textContent = "";
}

async function tearDownSession() {
  const current = session;
  session = undefined;
  if (current) {
    try {
      await current.close();
    } catch {
      // Already gone.
    }
  }
  disconnectBtn.hidden = true;
  connectBtn.disabled = false;
  clearDevice();
  setInstallEnabled();
}

function watchDisconnect(current: CarSession) {
  void current.adb.disconnected.then(async () => {
    if (session !== current) return;
    session = undefined;
    disconnectBtn.hidden = true;
    connectBtn.disabled = false;
    clearDevice();
    setInstallEnabled();
    setStatus(connectStatus, "החיבור לרכב נותק.", "warn");
    if (installing) {
      setStatus(installStatus, hebrew.disconnected, "err");
    }
  });
}


function mapInstallStatus(message: string): string {
  if (message.startsWith("Pushing the APK")) {
    return "דוחפים את ה־APK אל /data/local/tmp…";
  }
  if (message.startsWith("Pushing the session installer")) {
    return "דוחפים את עוזר ההתקנה (DEX) לרכב…";
  }
  if (message.startsWith("Installing via PackageInstaller")) {
    return "מתקינים דרך PackageInstaller (app_process)…";
  }
  if (message.startsWith("Session install did not report Success")) {
    return "התקנת הסשן לא דיווחה Success; נופלים ל־pm install -r -d -g…";
  }
  if (message.startsWith("Install blocked by user restriction")) {
    return "ההתקנה נחסמה במדיניות הרכב. מנקים את הגבלת ההתקנה ומנסים שוב…";
  }
  if (message.startsWith("Retrying pm install -r -d -g --user ")) {
    const user = message
      .replace(/^Retrying pm install -r -d -g --user\s+/, "")
      .replace(/…$/, "");
    return `מנסים שוב עם pm install --user ${user}…`;
  }
  if (message.startsWith("Retrying pm install")) {
    return "מנסים שוב עם pm install…";
  }
  if (message.startsWith("Listing users")) {
    return "קוראים את רשימת המשתמשים ברכב…";
  }
  if (message.startsWith("Trying:")) {
    return `מנסים לבטל הגבלה: ${message.slice("Trying:".length).trim()}`;
  }
  if (message.startsWith("pm install --user") && message.includes("failed to run")) {
    return "ניסיון התקנה למשתמש נכשל; עוברים למשתמש הבא…";
  }
  if (message.startsWith("Removing temporary")) {
    return "מוחקים קבצים זמניים…";
  }
  if (message.startsWith("Removing")) {
    return "מוחקים את הקובץ הזמני…";
  }
  if (message.startsWith("Running pm") || message.startsWith("falling back to pm")) {
    return "מריצים pm install…";
  }
  return message;
}

function checkBrowser() {
  const support = getWebUsbSupport();
  if (support === "ok") {
    browserBanner.hidden = true;
    connectBtn.disabled = false;
    return;
  }
  browserBanner.hidden = false;
  browserBanner.className =
    support === "insecure" ? "banner banner-warn" : "banner banner-err";
  browserBanner.textContent =
    support === "insecure" ? hebrew["insecure-context"] : hebrew["webusb-unsupported"];
  connectBtn.disabled = true;
}

connectBtn.addEventListener("click", () => {
  void (async () => {
    if (connecting || installing) return;
    connecting = true;
    connectBtn.disabled = true;
    setStatus(connectStatus, "פותחים את חלון בחירת המכשיר…", "warn");
    try {
      if (session) await tearDownSession();
      const next = await connectToCar((message) => {
        const he =
          message.startsWith("Select the car")
            ? "בחרו את הרכב בחלון של הדפדפן…"
            : message.startsWith("Opening the USB")
              ? "מתחברים ליציאת ה־USB…"
              : message.startsWith("Waiting for authorization")
                ? "ממתינים לאישור במסך הרכב. לחצו Allow (ומומלץ Always allow)…"
                : message.startsWith("Reading device")
                  ? "קוראים את פרטי המכשיר…"
                  : message;
        setStatus(connectStatus, he, "warn");
      });
      session = next;
      watchDisconnect(next);
      renderDevice(next);
      disconnectBtn.hidden = false;
      setStatus(
        connectStatus,
        `מחובר: ${next.info.model || next.info.usbName} · ${next.info.serial}`,
        "ok",
      );
    } catch (error) {
      const { text, detail } = friendlyError(error);
      setStatus(connectStatus, text, "err");
      if (detail) {
        showOutput(detail);
      }
    } finally {
      connecting = false;
      connectBtn.disabled = false;
      setInstallEnabled();
    }
  })();
});

disconnectBtn.addEventListener("click", () => {
  void (async () => {
    await tearDownSession();
    setStatus(connectStatus, "נותק.", "");
  })();
});

apkInput.addEventListener("change", () => {
  const file = apkInput.files?.[0];
  selectedFile = file;
  resetProgress();
  showOutput(undefined);
  setStatus(installStatus, "", "");
  if (!file) {
    fileLabel.textContent = "בחירת קובץ APK…";
    fileMeta.textContent = "";
  } else {
    fileLabel.textContent = file.name;
    fileMeta.textContent = `${formatBytes(file.size)} · ${file.type || "application/vnd.android.package-archive"}`;
  }
  setInstallEnabled();
});

installBtn.addEventListener("click", () => {
  void (async () => {
    if (!session || !selectedFile || installing) return;
    installing = true;
    setInstallEnabled();
    connectBtn.disabled = true;
    disconnectBtn.disabled = true;
    resetProgress();
    progressWrap.hidden = false;
    showOutput(undefined);
    setStatus(installStatus, "מתחילים התקנה…", "warn");
    try {
      const result = await installApk(session, selectedFile, {
        onStatus(message) {
          const he = mapInstallStatus(message);
          setStatus(installStatus, he, "warn");
        },
        onProgress(sent, total) {
          const pct = total > 0 ? Math.min(100, Math.round((sent / total) * 100)) : 0;
          progressFill.style.width = `${pct}%`;
          progressText.textContent = `הועלו ${formatBytes(sent)} מתוך ${formatBytes(total)} (${pct}%)`;
        },
      });
      setStatus(installStatus, "ההתקנה הצליחה.", "ok");
      showOutput(result.output);
    } catch (error) {
      const { text, detail } = friendlyError(error);
      setStatus(installStatus, text, "err");
      showOutput(detail ?? (error instanceof InstallerError ? error.message : errorMessage(error)));
    } finally {
      installing = false;
      connectBtn.disabled = false;
      disconnectBtn.disabled = false;
      setInstallEnabled();
    }
  })();
});

checkBrowser();
setInstallEnabled();
