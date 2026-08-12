# DawaSafe for Android

An installable Android app (phone and tablet) wrapping the DawaSafe medicine
checker, with **real Android alarms** for dose reminders — they fire at the
exact minute, they survive the app being closed, and they survive a reboot.

You do not need Android Studio, a Mac, or any programming knowledge. You need a
free GitHub account and about ten minutes. GitHub's computers build the app;
you download the finished file.

---

## Part 1 — Build the APK

### Step 1. Make a GitHub account

Go to <https://github.com> and sign up if you do not already have an account.
The free plan is enough.

### Step 2. Make an empty repository

1. Click the **+** in the top-right corner → **New repository**.
2. **Repository name:** `dawasafe-android` (any name works).
3. Choose **Private** unless you want it public. Both build the same.
4. Do **not** tick "Add a README" — the upload works best into an empty repo.
5. Click **Create repository**.

### Step 3. Upload this folder

If you were given **`DawaSafe-Android.zip`**, unzip it first. Everything below
refers to the folder that comes out of it.

On the new repository's page, click **uploading an existing file**.

Now drag in the **contents** of the `DawaSafe-Android` folder — not the folder
itself. When you are done the file list at the top of your repository should
start with `app`, `gradle.properties`, `settings.gradle`, and so on. If instead
you see a single `DawaSafe-Android` folder, you dragged one level too high:
delete it and drag the contents.

> **The hidden folder.** `.github` starts with a dot, so Windows Explorer and
> macOS Finder both hide it by default — and it is the folder containing the
> build instructions. Without it nothing will happen in Step 4.
> **Windows:** in Explorer, View → tick *Hidden items*.
> **Mac:** in Finder, press <kbd>Cmd</kbd>+<kbd>Shift</kbd>+<kbd>.</kbd>
> Then drag again, and check that `.github/workflows/build-apk.yml` appears in
> the uploaded list.
>
> Some browsers refuse to upload dot-folders by drag no matter what you tick.
> If `.github` will not go up, make it by hand instead: on the repository page
> click **Add file → Create new file**, type
> `.github/workflows/build-apk.yml` as the filename (GitHub turns each `/`
> into a folder as you type), paste in the contents of that file from the
> unzipped folder, and click **Commit changes**.

Scroll to the bottom and click **Commit changes**.

### Step 4. Wait for the build

Click the **Actions** tab. A run named **Build APK** starts by itself within a
few seconds.

- 🟡 yellow dot = building (three to six minutes on the first run)
- ✅ green tick = done
- ❌ red cross = something went wrong — see [Troubleshooting](#troubleshooting)

### Step 5. Download the APK

Click the finished run. Scroll to the bottom, to **Artifacts**. Click
**DawaSafe-APK** to download a `.zip`.

Unzip it. Inside is `DawaSafe-v8.0-build1.apk`. That is the app.

> GitHub always zips artifacts — there is no setting to turn that off. Unzip it
> on your computer before sending it to the phone, or the phone will try to open
> the zip and not the app.

---

## Part 2 — Install it on the phone or tablet

Send the `.apk` to the device (WhatsApp, email, a USB cable, or Google Drive).
Then, on the device, tap the file.

Android will warn you that this app came from outside the Play Store. That
warning is expected — it appears for every app that is not from the Play Store,
including this one.

- Tap **Settings** on the warning, turn on **Allow from this source**, then
  press Back and tap the file again.
- On older Android: Settings → Security → tick **Unknown sources**.

### First launch — three questions to say yes to

1. **"Allow DawaSafe to send you notifications?"** → **Allow.**
   Say no and dose reminders can never appear on screen.
2. **Alarms & reminders** (Android 12 and newer) — if the app shows an amber
   card asking for this, tap its button and switch it on. Without it Android
   may delay an 8:00 dose to 8:40.
3. **Battery** — see the next section. This one is not optional.

### The battery setting that actually matters

This is the single most common reason reminders stop, and no app can detect or
fix it from code.

Xiaomi, Redmi, POCO, Oppo, Realme, Vivo, OnePlus and Samsung all ship
aggressive battery savers that silently kill background apps — including their
alarms. The app shows a card with a button that takes you straight to the right
screen. Set DawaSafe to **Unrestricted** / **Don't optimise** / **Allow
background activity**.

On Xiaomi/Redmi/POCO there is a *second*, separate setting that catches people
out: Settings → Apps → DawaSafe → **Autostart** → turn **on**. Without autostart
the reminders will not come back after a reboot.

---

## Moving to a new phone

The app deliberately does **not** back up to Google Drive. Its own privacy card
says the data stays on the device, and Android auto-backup would quietly make
that untrue. So the transfer is manual, and it takes a minute:

1. **Old phone:** open DawaSafe → Settings → **Backup & restore** → **Export my
   data**. Save the file, then send it to yourself (email, WhatsApp, Drive).
2. **New phone:** install the APK, open DawaSafe → Settings → **Backup &
   restore** → **Import data**, and pick the file. You get a preview, then
   confirm.

Everything comes across: profiles, medicine lists, schedules and dose history.

Do this before factory-resetting or giving away the old phone. Uninstalling the
app deletes its data.

---

## Updating to a newer version later

Rebuild (Step 4 — or use **Run workflow** on the Actions tab), download the new
APK, and install it straight over the old one. **Data is kept.**

This works because the project commits a signing key, so every build is signed
with the same certificate. Android only allows an update over an existing
install when the signature matches. Without that committed key you would have to
uninstall first — losing the medicine list.

That key is in `keystore/dawasafe.jks` and its password is in `app/build.gradle`,
in plain sight. That is fine for sideloading to your own family's phones, and
**not** fine for the Play Store: anyone with this repository can sign something
that Android will accept as an update to your app. If you ever publish, generate
your own key and keep it out of git.

---

## Troubleshooting

**The Actions tab is empty, or no run ever started.**
`.github` did not upload — it is hidden by default. See the note in Step 3.

**Red cross: "DawaSafe-v8.html is missing from the repository root".**
The app itself did not get uploaded. Confirm the repository has `DawaSafe-v8.html`
at the top level, around 3.8 MB. (The build copies it into the APK for you —
you do not need to put it in an `assets` folder yourself.)

**Red cross: "this looks like a pre-v8 export".**
`DawaSafe-v8.html` is an older DawaSafe build without the Android alarm bridge.
Replace it with the v8 file.

**Red cross on "Build release APK" at `Task :app:stageWebApp FAILED`, when the
step before it went green.** Those two steps check the same file, so if the
first passed, the file is fine and the build script is wrong. This was a real
bug, fixed on 2026-08-11: the Gradle check read only the first 400,000
characters of the app looking for its version marker, but the built-in medicine
index is a single line of ~3.2 MB, so every marker sits past character
3,270,000. The check could not see what it was looking for and failed every
build on a perfectly good file. If you see this, your `app/build.gradle`
predates the fix — replace that one file and re-run.

**Any other red cross.** Click the run, then the failed step, to expand the log.
The last twenty lines almost always name the problem.

**The app installs but shows a blank white screen.**
The WebView is out of date. Open the Play Store and update **Android System
WebView** and **Chrome**, then reopen the app.

**Reminders do not fire when the app is closed.**
Nearly always the battery setting above. Check, in order: notifications allowed;
"Alarms & reminders" allowed; battery set to unrestricted; on Xiaomi/Redmi/POCO,
Autostart on.

**Reminders stopped after a reboot.**
Open the app once — that re-arms everything. If it recurs, it is Autostart.

---

## What is in this folder

| Path | What it is |
|---|---|
| `DawaSafe-v8.html` | The entire DawaSafe app, one file. The build copies it in |
| `.github/workflows/build-apk.yml` | The build instructions GitHub follows |
| `app/src/main/java/com/dawasafe/app/` | The Android side: alarms, notifications, the JS bridge |
| `app/src/main/AndroidManifest.xml` | Permissions, with the reasoning for each |
| `keystore/dawasafe.jks` | The signing key — see the warning above |

---

## What the app does and does not claim

DawaSafe checks for interactions between medicines using a local, offline
database. It is a **prompt to ask a doctor or pharmacist — not medical advice,
and not a substitute for one.** It does not diagnose, and it never invents drug
information: everything it shows comes from its bundled data, and where it does
not know, it says so.

The reminder system reports its own state honestly. If notifications are off, if
exact alarms were refused, or if a reboot may have cleared the schedule, the app
says so on the schedule screen rather than showing a reassuring green tick it
cannot back up.
