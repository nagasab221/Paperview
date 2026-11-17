# PaperView

**A free PDF reader for Android. No ads, no accounts, no subscriptions.**

Read, scan, sign and edit your PDFs, all on your phone. Your documents stay on your device unless you choose to back them up to your own cloud.

### [⬇ Download the latest version](https://github.com/nagasab221/Paperview/releases/latest)

---

## What it can do

### 📖 Comfortable reading
- Smooth scrolling and pinch-to-zoom that stays sharp, even zoomed right in
- Opens right where you left off: same page, same zoom
- Search the document, copy text, and tap links to follow them
- Table of contents and page thumbnails in a side panel
- Jump to any page in seconds with the scrollbar, or tap the page number
- Continuous scrolling or one page at a time, and rotate if needed
- Opens password-protected PDFs

### 👁 Peek: check the answer key without losing your place
Made for working through questions while the answers are somewhere else in the document.

1. Go to the answer key and choose **⋮ → Set peek spot here**.
2. An **eye button** appears next to the page number.
3. **Hold** the eye to see the answer key, then let go and you're back on your question.
   **Tap** it to go there, and tap again to come back.

Each document remembers its own peek spot. Move or remove it from the same **⋮** menu.

### 📚 Your library
- All your recent PDFs on a shelf, showing their first page and the page you're on
- A separate **Cloud** tab for documents saved to your own online storage (optional, see below)
- Long-press a cover to remove it from the shelf

### 📷 Scan paper documents
Point your camera at a page. PaperView finds the edges, straightens it, and cleans it up, removing shadows and grey lighting so the paper comes out white. Choose from four looks: **Original**, **Document**, **Greyscale** or **Ink** (pure black and white).

### 🛠 14 free PDF tools
Nothing is locked behind a paywall, and nothing is uploaded anywhere to do the work.

| Organise | Convert | Protect |
| --- | --- | --- |
| Merge PDFs into one | PDF to image (JPG/PNG) | Sign with your finger |
| Split into separate files | Image to PDF | Add a watermark |
| Reorder pages | Compress (make smaller) | Lock with a password |
| Delete pages | | Change password |
| Rotate pages | | Unlock (remove password) |
| Add page numbers | | |

### 🎨 Make it yours
- Three styles (**Paper**, **Slate** and **Ink**), each in light and dark
- Set PaperView as your default PDF app, so PDFs from WhatsApp, email or your files open straight in it
- Print or share any document

---

## Install

1. On your phone, open the [latest release](https://github.com/nagasab221/Paperview/releases/latest) and download the file ending in **`-arm64.apk`**. That's the right one for almost every phone.
   *Not sure, or it won't install? Take the `-universal.apk` instead.*
2. Open the downloaded file. Android will ask you to allow installs from your browser or file manager. Allow it, then tap **Install**.
3. Android may warn that the app isn't from the Play Store. That's expected for apps installed directly.

**Updating:** download the new version and install it over the old one. Your library, reading positions and settings are kept.

**Needs:** Android 8.0 or newer.

---

## Optional: keep your PDFs in your own cloud

Add one or more cloud accounts and switch between them on the Library's **Cloud** tab. It shows the account's folders, so you can browse, make new folders, and upload PDFs or a whole folder with its subfolders. Nothing is uploaded unless you ask. These are the same account types as the [desktop app](https://github.com/nagasab221/PaperView-Desktop), and both apps see the same files:

| Account type | Works with |
|---|---|
| **SSH** | A computer or NAS you can `ssh` into. PaperView makes its own key for this phone; add it to the server's `~/.ssh/authorized_keys` (or enter the password). |
| **WebDAV** | Nextcloud, ownCloud, Synology, QNAP, TrueNAS… |
| **S3** | Cloudflare R2, Wasabi, AWS S3, MinIO… |
| **Backblaze B2** | A private B2 bucket (10 GB free) |
| **Folder** | Any folder the system folder picker offers, including ones a NAS or Nextcloud app shares. Documents open straight from it. |

1. Go to **Settings → Cloud → Add account**, pick the type and fill in the details.
2. Tap **Connect and save**. PaperView connects first and shows what it's doing. The account is saved only once it answers, and if it can't connect, it tells you why (wrong password, unknown host, nothing answering…). **Test connection** in each account's menu checks it again later.
3. To upload, use **Add…** on the Cloud tab, or open a document and choose **⋮ → Save to cloud**. It goes into the folder the Cloud tab is showing.

Passwords and keys are encrypted with a key kept in the phone's Keystore. Don't want the Cloud tab? Switch it off in Settings at any time.

---

## Handy tips

| Do this | To |
| --- | --- |
| Tap the page | Hide or show the toolbars |
| Double-tap | Zoom in, or back out |
| Long-press a word | Select text, then drag the handles |
| Drag the scrollbar on the right | Fly through long documents |
| Tap the page number | Go to a specific page |

---

## Privacy

- No ads, no analytics, no accounts.
- Reading and all 14 tools work offline, on your phone.
- The scanner uses the document scanner built into Google Play services, so it needs a phone with Google Play. The very first scan may download it. Your scans are processed on the phone.
- Otherwise the internet is only used if you set up the optional cloud backup, and then only to talk to the accounts you added.

---

<sub>For developers: built with Kotlin and Jetpack Compose, using PDFium for display and PDFBox for editing. Build with `./gradlew assembleRelease` (JDK 17, Android SDK 35). How it all works, and why, is written up in [docs/DEVELOPER_NOTES.md](docs/DEVELOPER_NOTES.md).</sub>
