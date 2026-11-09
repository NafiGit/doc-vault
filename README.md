# Doc Vault — personal documents as a floating bubble

A small native Android app for keeping your own documents close at hand,
DigiLocker-style, but as a **floating widget over other apps** instead of
another app you have to open, search, and leave.

Tap the app icon once and a small bubble appears over whatever you are
doing — WhatsApp, Chrome, anything. Tap the bubble and it expands into a
small rectangle with your folders. Tap a folder and a second rectangle
slides up with the files inside as icons. Tap a file to open it, or
long-press / drag it out to send it into WhatsApp.

It keeps my school, university, and office documents in one place,
private and local on my phone, and reachable in one tap from the
bubble: School (Class X and Class XII certificates), University
(transcripts Sem 1–8, degree, CMM, PCC, Transfer Certificate), and
office documents. Nothing leaves the device unless I share a file
myself.

## What it is

- **Native Android, Kotlin + Jetpack Compose.** Package
  `com.nahfid.docvault`, minSdk 26, targetSdk 34, compileSdk 34.
- **Local only.** Documents live in app-private storage
  (`files/vault/<folderId>/`) and the folder/file index is one JSON
  file (`files/vault.json`). No account, no cloud, no server, no
  analytics. Nothing is uploaded anywhere.
- **Seeded structure, no real documents.** The first run creates the
  School / University / Office folders above, with placeholder entries naming
  the kind of document that belongs there (e.g. "Class X Certificate.pdf").
  Tapping a placeholder prompts you to import the real file. **No real
  certificate, transcript, ticket, key or personal file is in this repo.**

## How the bubble → folder → file → WhatsApp flow works

1. **Open the app, tap "Show floating bubble".** Android asks you to
   grant **Draw over other apps** (Settings → Apps → Doc Vault →
   "Display over other apps"). Without it the bubble cannot appear;
   that permission can only be granted by the user, in Settings.
   On Android 13+ you are also asked for notification permission,
   because the bubble runs as a foreground service.
2. **A small bubble appears** (teal circle, folder icon), draggable
   anywhere on screen. Dragging moves it; a plain tap expands it.
   The bubble is owned by `BubbleService`, a foreground service, so
   it survives leaving the app. An ongoing notification ("Doc Vault
   bubble is on") lets you hide it.
3. **Tap the bubble → rectangle panel.** It expands with animation
   into a 320dp-wide panel listing folders. Top-level folders are
   School and University; tapping one shows its sub-folders
   (Class X / Class XII, Transcripts / Degree / CMM / PCC /
   Transfer Certificate). Long-press a folder's drag handle and drag
   up/down to reorder it. `+` adds a folder.
4. **Tap a leaf folder → second rectangle slides up.** `FilesPanel`
   animates in from below the folder panel with the files as icons:
   red PDF icon, green image icon, grey generic icon. Placeholders
   say "tap to import".
5. **Tap a file to open it** (PDF/image viewer via FileProvider).
   **Share button** on a file opens the system share sheet with the
   file attached — WhatsApp appears as a target, pick a chat, send.
   **Drag out:** long-press a file icon, then drag it out of the
   bubble and drop it on WhatsApp. That uses Android's global
   drag-and-drop (`DRAG_FLAG_GLOBAL`) with a FileProvider URI.
6. **Import:** tap `+ Import` (or a placeholder). The bubble hands
   off to the main screen, which opens the system file picker
   (Storage Access Framework). Pick a PDF or image; it is copied
   into app-private storage and replaces the same-named placeholder.

## Project layout

```
doc-vault-android/
  settings.gradle.kts / build.gradle.kts / gradle.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml   SYSTEM_ALERT_WINDOW + FOREGROUND_SERVICE,
                                     BubbleService, FileProvider
  app/src/main/java/com/nahfid/docvault/
    MainActivity.kt                  permission gate, bubble switch,
                                     full-screen vault, SAF import picker
    DocVaultApp.kt                   Application, owns the repository
    bubble/BubbleService.kt          foreground service, overlay window,
                                     Compose lifecycle owners for a Service
    bubble/BubbleOverlay.kt          BubbleRoot, BubbleButton, VaultPanel,
                                     FolderRow (drag reorder), FilesPanel,
                                     FileTile (tap / share / drag-out)
    data/Models.kt                   VaultFolder / VaultFile / VaultState
    data/SeedData.kt                 School + University folder seed
    data/VaultRepository.kt          JSON store + app-private files,
                                     add/rename/delete/move, import
    ui/VaultViewModel.kt             shared state for bubble + full screen
    ui/Theme.kt                      Material 3 theme (teal)
    share/ShareHelper.kt             FileProvider URIs, ACTION_SEND share,
                                     ACTION_VIEW open, startDragAndDrop
  app/src/main/res/xml/file_paths.xml  FileProvider: only files/vault/
```

Data layer choice: a single JSON file instead of Room. The data is
tiny (folders + file metadata), the only query is "files in this
folder, in order", and it avoids annotation processing for a first
version. If the vault grows past a few hundred files or needs search,
swap `VaultRepository` for Room behind the same public API.

## Build and install

Prerequisites: Android Studio (Hedgehog or newer), JDK 17, Android
SDK 34. The Gradle wrapper is configured for Gradle 8.5; open the
project in Android Studio and let it sync, or from a terminal:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open Doc Vault, tap "Show floating bubble", grant "Display over
other apps" when Settings opens, and go back — the bubble appears.

> **Build status: a debug APK was built successfully on Nov 9, 2026**
> (`assembleDebug`, Gradle 8.5, JDK 17, SDK 34) — `app-debug.apk`,
> 15,648,828 bytes, debug-signed, package `com.nahfid.docvault`.
> Install it with `adb install -r app-debug.apk`. It is a debug build
> for testing on a real phone; the bubble gestures, overlay
> permission on OEM skins, and drag-to-WhatsApp are not yet
> device-tested.

## Honest limits

- **Overlay permission is a user step.** Android will not let an app
  grant itself "Draw over other apps". First launch must go through
  Settings, and some OEM skins (Xiaomi/MIUI, Oppo/ColorOS, Vivo,
  Samsung) add a second "allow pop-up window / floating window"
  toggle in their own settings. If the bubble does not appear after
  granting, check the OEM floating-window setting and battery
  optimisation (unrestricted / not optimised) for Doc Vault.
- **The bubble needs the foreground service alive.** If Android kills
  it under memory pressure, reopen the app and tap "Show floating
  bubble" again. The notification's "Hide bubble" action stops it.
- **Drag-to-WhatsApp is best-effort.** The share sheet is the
  reliable route: WhatsApp always accepts a shared file intent.
  Drag-and-drop depends on the WhatsApp version accepting a dropped
  content URI; if a drop does nothing, use the share button instead.
- **Drag reorder is long-press + vertical drag** on the handle, one
  row per ~72dp of movement. It is deliberately simple; a fling-style
  reorder library would be smoother.
- **No backup, no sync, no lock (yet).** Uninstalling the app deletes
  the vault (app-private storage). There is no PIN/biometric lock in
  v0.1.0 — add one before putting sensitive originals in if the phone
  is shared. Back up the files yourself before uninstalling.
- **Placeholder names are generic.** They name document kinds, not
  your real documents. Import replaces them with your real files,
  which never leave the device except when you share one.
