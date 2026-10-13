# PaperView for Android

The mobile build of [PaperView](../PaperView) — the same lightweight PDF viewer,
the same renderer, the same palette — plus a 14-tool PDF editing suite.
Kotlin + Jetpack Compose over PDFium (rendering) and PDFBox (editing).

**Release APK: 9.9 MB** (arm64). Four tabs: Library, Scan, Tools, Settings.

## Tools

All 14 run entirely on-device. Nothing is paywalled and nothing is uploaded.

| | |
| --- | --- |
| **Modify** | Compress, Merge, Split, Rotate, Delete Pages, Reorder, Number Pages |
| **Convert** | PDF → Image (JPG/PNG, page range, dpi), Image → PDF |
| **Security** | Watermark, Sign, Lock, Change Password, Unlock |
| **Scan** | Camera capture with automatic edge detection, straight to PDF |

Deliberately **not** included: PDF↔Word/PPT conversion. Faithful Office
conversion means reconstructing layout, fonts and tables; it can't be done well
on-device, and a converter that mangles documents is worse than none. Read Aloud
was dropped by request.

## What's here

Everything the desktop build does, translated where the platform demands it:

| Desktop | Android |
| --- | --- |
| Continuous scroll + single-page mode | Same, with pinch-zoom and fling; in single-page mode a drag past the page edge flips |
| Text search (PDFium, yellow / orange highlights, F3 cycling) | Same matcher, same colours; incremental scan from the current page with a progress bar |
| Text selection & copy | Long-press selects a word, drag the handles to extend, cross-page, Copy / Select all |
| Clickable links | Tap to follow — internal jumps push onto the back stack, URIs open in the browser |
| TOC sidebar + thumbnails | One drawer with Pages / Contents tabs |
| Whole-document rotation | Rotate left / right in the overflow menu |
| Password-protected files | Same prompt, wrong-password retry |
| Per-file memory (page, zoom, rotation) | Same, in the Recent list |
| Recent + Saved landing page | Same two tabs |
| Backblaze B2 "Saved" tab | Same bucket, same stats card (usage vs the 10 GB free tier, counts, largest, cache) |
| Printing (hand-rolled Win32 + in-app preview) | Android's print pipeline — preview, ranges, copies and PDF export come free |
| `.pdf` file association via the registry | Manifest intent filters + a "make default" flow in Settings |
| Light / Dark / System | Same |

**Deliberately different**

- **Single document at a time** instead of tabs — one document fills a phone
  screen, and the Recent list is the way back.
- **Upload is manual only.** "Save to cloud" uploads; nothing uploads on its
  own. The desktop's auto-save-on-open would spend mobile data without asking.
- **Two ways to reach files.** The system picker (SAF) is the default and needs
  no permission; an opt-in switch in Settings grants all-files access and adds
  an in-app folder browser.

## Building

Needs Android Studio's bundled JDK; there is no standalone `java` on this
machine.

```bash
JAVA_HOME="$ProgramFiles/Android/Android Studio/jbr" ./gradlew assembleDebug
```

Release APKs, split per ABI, into `dist/`:

```bash
powershell -ExecutionPolicy Bypass -File scripts/package.ps1
```

Install the `arm64` one on any phone from roughly 2015 onwards:

```bash
adb install -r dist/PaperView-1.0.0-arm64.apk
```

Unit tests (layout and coordinate math, 14 of them):

```bash
JAVA_HOME="$ProgramFiles/Android/Android Studio/jbr" ./gradlew testDebugUnitTest
```

## Layout

```
pdf/PageGeometry.kt   layout stack + page<->view transforms (pure Kotlin, unit-tested)
pdf/PdfEngine.kt      PDFium init; one dedicated thread for all native calls
pdf/PdfDoc.kt         open, measure, render, text, search, links, outline
pdf/PageCache.kt      rendered-page LRU keyed (page, pixel width, rotation)
viewer/DragLock.kt    which way a drag may move the page (pure Kotlin, unit-tested)
viewer/               ViewModel, gesture/canvas viewport, chrome
landing/              Recent + Saved tabs
cloud/                Backblaze B2 client and its operation queue
files/                SAF helpers and the opt-in folder browser
store/Shelf.kt        private copies of documents Android won't let us reopen
print/ assoc/ settings/ store/
```

## Things worth knowing

- **PDFium access is serialized on one thread.** Its thread safety only makes
  individual FFI calls atomic; interleaving page, text and search sessions
  corrupts results even across separate documents. The desktop build has a
  global mutex for the same reason.
- **Page rotation composes from two sources.** PDFium applies a page's own
  `/Rotate` when rendering but reports the CropBox and all text coordinates
  *unrotated*. `PageBox.intrinsic` carries the page's own rotation and composes
  it with the user's; render does the page naturally and turns the raster.
  Mixing those up misplaces every highlight, silently.
- **The CropBox origin is subtracted from text coordinates.** A CropBox that
  doesn't start at (0,0) offsets every overlay otherwise — the same bug the
  desktop build hit.
- **Pages render at whole-pixel sizes.** Rendering at a fractional width and
  stretching is what made zoom look soft on the desktop.
- **Zoomed pages are drawn from tiles, not one big bitmap.** An A4 page at 3x on
  a 1440px screen is 4248x6012 px — over 100 MB, more than the whole cache, so
  it evicted itself every frame and the viewer stuttered. A whole-page render
  capped at `PREVIEW_MAX_WIDTH_PX` acts as a stretched backdrop, and 512px tiles
  of the *visible region only* are drawn sharp on top. PDFium has no
  render-sub-rectangle call, but its draw origin may be negative, so a tile is
  the whole page positioned at `-tileLeft, -tileTop` in a tile-sized bitmap.
- **Cloud rows carry their own state.** A file is on the device, queued,
  downloading, or bucket-only, and the list is split into "On this device" and
  "In your bucket" so the difference is visible without reading anything. A tap
  marks the file queued *before* any network call, because the alternative —
  a row that looks identical for several seconds — reads as a broken button.
  Progress and speed render on the row itself, not in one shared bar.
- **The on-device list is read from disk, not from the bucket listing.**
  Deriving it by filtering the remote listing meant every downloaded document
  disappeared the moment the network did — the files were right there on the
  phone and unreachable. `CloudViewModel.localFiles` is populated straight from
  the cache directory, so the "On this device" group and opening those files
  need no network at all. A failed listing is shown in muted text rather than
  as an error when local copies exist.
- **The B2 session is cached for six hours.** Re-authorising before every
  operation added two round trips to each download, which was most of the delay
  between tapping a file and seeing anything happen. Tokens are good for 24h;
  a failure clears the cached session so a stale token can't poison later work.
- **Every directory the app shares from must be a FileProvider root.** Files
  are written to `cache/share` (documents) and `cache/tools` (tool output);
  when `file_paths.xml` only declared `cache/cloud`, `getUriForFile` threw and
  *all* sharing failed. `res/xml/file_paths.xml` now declares the cache root
  wholesale, and `everyShareableDirectoryHasAFileProviderRoot` fails if a new
  output directory is ever added without covering it.
- **Share intents need the URI in `clipData` too.** Some receivers — WhatsApp
  among them — only honour the read grant when the URI appears there as well as
  in `EXTRA_STREAM`, and the chooser intent needs the grant flag of its own.
- **The viewer's bars float over the page; they never inset it.** As a
  Scaffold's `topBar` and `bottomBar` they took layout space, so tapping to show
  them resized the reading area: `onSizeChanged` fired, `setViewport` recomputed
  the fit-to-page scale against the new height and re-clamped the scroll. The
  page visibly shifted on every tap and shifted back on the next one. The
  reading area is now a constant — the system-bar inset and nothing else — and
  the bars are overlaid, which also means the keyboard opening for search can't
  move the page either. The cost is that an opaque bar covers a strip of the
  page while it's shown, which is the right trade: a reader can tap it away, but
  it can't un-lose its place.
- **A document opened from another app can only be read once.** Tapping a PDF in
  a file manager or a chat starts the app with a one-shot read grant:
  `takePersistableUriPermission` throws for it, and the grant dies with the task.
  The shelf filters out entries it can no longer open, so every such document
  silently vanished from it at the next launch — and since PaperView is the
  default PDF app, that was nearly all of them, which is why the shelf always
  looked empty. `FileAccess.persist` now *reports* whether the grant could be
  kept, and when it couldn't, `Shelf` copies the bytes into `files/shelf` and the
  document is opened and remembered from there. Picker-opened documents are
  untouched by any of this — that grant is persistable, so those keep reading the
  original and show edits made to it elsewhere. Copies are named after a digest
  of **the document's own bytes**, capped at 80 MB each and 200 MB in total,
  evicted least-recently-read first, and excluded from cloud backup: 200 MB of
  someone's reading has no business leaving the phone.
- **A copy is named after its contents, never after where it came from.** The
  first version keyed copies by the source URI, and a chat app mints a fresh URI
  for every share — so the same PDF sent from WhatsApp three times was stored
  three times, listed three times, and, the part that actually hurt, carried
  three separate reading positions, so reopening it from the chat always started
  from page one. Identical bytes now resolve to one copy and one entry. The size
  a provider reports is only a hint and is often zero, so the digest is taken
  while the bytes are streamed in and the size cap is enforced against what
  actually arrives, not against what was promised. `Shelf.rekeyByContent` repairs
  shelves written the old way, once, and `Recents.collapse` makes the list
  follow: the survivor of a group is the newest entry *that was read into*, not
  simply the newest, because the newest is usually the row the bug just reset to
  page one.
- **Every read-modify-write of the Recent list goes through one mutex.** Four
  callers rewrite it from three coroutine scopes — opening a document, forgetting
  one, the consolidation, and the viewer saving your place every 600ms as you
  read. Each reads the whole list, edits it and writes it back, so two
  overlapping meant the second silently undid the first. DataStore makes each
  *write* atomic, which is not the same thing.
- **Staged copies for sharing are swept.** Every share, print and upload writes
  a full copy into `cache/share` and nothing removed it, so it grew without
  limit — faster once shelf copies, being digest-named, started being renamed on
  the way out. Files older than six hours go on the next write; nothing is
  deleted promptly, because a receiver may still be reading the URI it was given.
- **A drawer sheet is composed whether or not it is on screen.** The viewer's
  thumbnail list therefore began rendering pages the moment a document opened,
  on the single thread PDFium is allowed to use, ahead of the page being read.
  It escaped notice because a document *with* an outline opens on the Contents
  tab; it hit exactly the shared and scanned PDFs that have none. The lists are
  composed on first open and latched after.
- **A shared file is renamed before it leaves.** A shelf copy is named after a
  hash and a download after whatever the bucket called it; `materialize` copies
  either into `cache/share` under the document's real title, because the
  filename is what the person on the other end sees.
- **Zoom out stops at one page.** The floor is the fit-page scale for the
  current page, not a fixed constant — zooming out to a wall of thumbnails isn't
  reading.
- **The content block is centred in the viewport.** `pageLeftPx` only centres
  pages against the widest page in the *document*, which says nothing about the
  screen; when the block is narrower than the viewport (every zoomed-out
  landscape view) `contentOriginX` centres it. Without that the page sits pinned
  to the left and all the slack collects on the right. Anything converting
  between screen and content coordinates — drawing, hit-testing, selection
  handles, pinch focus — must apply the same offset.
- **Landscape opens at fit-width**, because a whole page there is small and
  margin-heavy. The zoom-out floor is still a whole page. A zoom set by hand
  (`Zoom.Factor`) survives rotation untouched.
- **A pinch zooms and nothing else.** Two fingers change scale about their
  midpoint; the page never translates, so there is no drift to chase. One
  finger scrolls. The midpoint is latched when the second finger lands rather
  than read live each frame: re-reading it anchors the zoom somewhere slightly
  different every frame, and the page creeps along after the fingers.
- **The pinch's share of the touch slop is the change in finger *spread*.**
  It was `centroid.getDistance()` — the centroid's distance from the screen
  origin, around a thousand pixels and nothing to do with the gesture — so the
  faintest two-finger contact cleared the slop on its first frame.
  `calculateCentroidSize()` is the quantity that was wanted.
- **`calculatePan()` is discontinuous when the pointer *set* changes.** It
  reports movement of the centroid of all pressed pointers, so the frame a
  second finger lands the centroid teleports from one finger to the midpoint of
  both — hundreds of pixels — and lifting reverses it. Applying that is a
  visible lurch. Any frame where the set of pointer ids changes contributes no
  movement; comparing the ids rather than how many there are also catches one
  finger landing as another lifts. When one finger remains the axis lock starts
  over, but when none do the committed axis has to stand, because the fling
  decision is still about to read it.
- **A gesture that involved two fingers never flings.** Releasing one finger of
  a pinch left the survivor's handful of samples looking like a flick, so the
  page drifted after zooming.
- **A one-finger drag locks to an axis, and "ambiguous" means vertical.** No
  thumb swipes in a straight line, and letting the sideways component through
  made the page feel like it was sliding around while reading. Two details
  decide whether the lock actually holds. The direction must be judged from
  enough travel to mean something — deciding the instant slop is crossed reads
  about eight pixels of noise — so it commits at `AXIS_COMMIT` times the slop,
  with vertical movement allowed through in the meantime so no scroll starts
  with dead travel. And a drag that looks diagonal is a wobbly vertical scroll
  far more often than a deliberate diagonal pan: treating it as diagonal is
  exactly the drift being complained about, so only a pinch unlocks both axes.
  `DragLock` holds that logic in pure Kotlin, with unit tests, because the
  gesture loop around it can only be exercised by a finger.
- **A finger that had stopped moving must not fling.** Android sends no move
  events while a finger holds still, so a velocity tracker's newest sample can
  predate the lift by a long way; asked to extrapolate across that gap it
  reports the speed the finger had *before* it stopped. The page then coasts
  away from a reader who had deliberately come to a halt — "scrolling a lot then
  letting go" and watching it slide. A flick now also has to be live: the last
  real movement within `STALE_FLING_MS` of the release.
- **On the final up event `pressed` is empty.** Treating that as multi-touch and
  resetting the velocity tracker wiped the history one moment before the fling
  read it, so flinging silently never happened at all — only a genuine
  two-finger event resets now, and the release position is recorded.
- **`FLING_BOOST` gives more reach than stock.** Android's fling is tuned for
  lists, where a screen or two per flick is plenty; a few hundred PDF pages
  needs more travel. The deceleration curve is still the platform one.
- **A position scrubber handles long jumps.** Flicking can't cross 1200 pages
  at any sane friction. The readout must render *outside* the narrow track —
  squeezed into the 36dp touch column it was unreadable — and the thumb fades to
  a hint rather than vanishing, because a scrollbar you can't see or touch is
  no better than none.
- **Fling velocity comes from one identified pointer.** Tracking "whichever
  pointer is first" meant lifting a finger after a pinch teleported the tracked
  position to the other finger, which the tracker read as enormous velocity and
  turned into a runaway fling. The tracker resets whenever the pointer set
  changes. The decay spec also needs the *real* screen density — building it
  with `Density(1f, 1f)` while working in physical pixels makes flings travel
  roughly density-times too far.
- **Auto-capture cannot be turned off.** `GmsDocumentScannerOptions` declares
  `CAPTURE_MODE_AUTO` and `CAPTURE_MODE_MANUAL`, but its Builder has no
  `setCaptureMode` — in the beta or in stable 16.0.0. The options object holds
  two ints, the page limit and the scanner mode, so there is nothing to reach
  by reflection either, and the client AAR ships no resources at all: the UI is
  `DocumentScanningActivity` inside Play Services. Its camera screen does have
  an Auto/Manual toggle, and switching it does not survive closing the scanner,
  so it costs one tap per session. Defaulting to manual would mean our own
  CameraX capture screen, which would also give up ML Kit's edge detection —
  that exists only inside the UI we would be replacing.
- **A scan is cleaned up by us, not by ML Kit.** ML Kit finds the page and
  corrects the perspective, which is the hard part and worth keeping, but it
  hands back the *photograph*: grey where the light fell off, darker still
  where a hand shadowed it. Its own filters sit a tap deep in its editor, reset
  for every capture, and cannot be preselected through
  `GmsDocumentScannerOptions` — so scans came out as snapshots of paper. We now
  take the individual JPEG pages instead of its finished PDF, run `Enhance`
  over them and rebuild the document with the PDFBox routine the Image to PDF
  tool already uses. No new dependency: OpenCV would have done this better and
  cost 15 to 25 MB per ABI, against an app that is 10 MB in total.
- **The cleanup is one idea: divide the page by its own background.** Estimate
  how bright the paper is at each point, then scale every pixel so paper
  becomes white. Three details carry it:
  - The estimate takes the **brightest** pixel of each block, not the average,
    because paper is the brightest thing in any small patch. Averaging lets a
    dense paragraph drag the estimate down, and dividing by that washes the
    paragraph out — the text fades exactly where there is most of it. A
    dilation first lets surrounding paper spill into cells that are nothing
    but ink.
  - It lives on a **thumbnail**. Illumination is low-frequency, so a 384px
    estimate is right to within a shade and costs almost nothing; bilinear
    sampling brings it back to full size.
  - It is **floored at half the page's own paper level**. Without that, a
    diagram or a photograph on the page is a large dark region the estimate
    follows faithfully, so it gets divided by itself and comes out pure white —
    measured at 255, every figure in a page of notes erased. The floor caps the
    correction where the "background" is too dark to be paper at all. It costs
    some shadow correction and is worth it.
  `Ink` adds Sauvola's local threshold on top, with the mean and variance taken
  from the same thumbnail. All of it is plain Kotlin on an `IntArray` with no
  Android types, so `EnhanceTest` can check on the desktop that a synthetic
  shadowed page really does come out flat, white and still legible.
- **Scans are written to `files/scans`, not the cache.** They used to go
  through `FileAccess.materialize` into `cache/share` — a directory the system
  may clear whenever it wants the space, and which the staging sweep now prunes
  after six hours. A scan is the only copy of its document.
- **The themed icon needs its own drawable.** When a launcher tints every app to
  one colour (Android 13+ themed icons; "apply palette to app icons" on One UI),
  the system throws the `<monochrome>` layer's colours away and paints every
  opaque pixel in the theme's colour. Pointing it at the colour foreground — the
  template default — meant the page body, drawn as a large *filled* shape,
  became a solid slab with the fold, the text hints and the PDF badge dissolved
  into it: a blob. `ic_launcher_monochrome.xml` draws the same page as an
  *outline* with the fold as its one solid mass, so the detail comes from empty
  space, and drops the badge, which could only ever be another blob. Its artwork
  sits inside the 66dp circle the themed spec asks for, tighter than the 72dp
  adaptive safe zone, because this layer is scaled up further.
  `LauncherIconInstrumentedTest` renders the layer and fails if the page
  interior is mostly ink again.
- **Credentials.** The B2 application key lives in app-private DataStore in
  plain text, as on the desktop. It is included in a device-to-device transfer
  but excluded from cloud backup, so it doesn't leave the phone.

## Design

The look is **editorial, not app-like**. Paper-toned surfaces, a serif face for
anything that announces itself against a sans body, hairline rules instead of
cards, and no drop shadows anywhere. Colour is reserved for things that mean
something — the active tab, a primary action, the current search hit.

Three complete styles, each defining its own light and dark rendition, chosen in
Settings:

| | |
| --- | --- |
| **Paper** | Warm off-white, sepia accent — the default |
| **Slate** | Cool grey, steel-blue accent |
| **Ink** | High contrast, no hue at all; hierarchy from weight and rules |

Everything is built from a small set of components in
`ui/components/Paper.kt` — `SectionHeading`, `PaperRow`, `PaperButton`,
`PaperField`, `Hairline`, `PaperMasthead`. **Screens should compose those rather
than reach for Material defaults**; that's what keeps the app looking like one
thing. A screen that needs a card or an elevation is off-theme.

The Library is a shelf of first-page covers rendered by PDFium and cached to
`cache/covers` (see `landing/ThumbnailStore.kt`), keyed by URI *and* file size so
a changed document re-renders. Tools are grouped list rows, not a grid of
coloured tiles.

## Why two PDF libraries

PDFium renders beautifully but can barely edit: its public API has no
`ImportPages` and no way to create a document, so merge, split, reorder and
encryption are impossible with it. PDFBox does the composing. Rendering,
search, selection and links stay on PDFium so the viewer matches the desktop
build exactly.

PDFBox drags in BouncyCastle, whose post-quantum parameter tables (SIKE,
Picnic) are **~4 MB of pure dead weight** — PDF encryption only uses RC4 and
AES. They're excluded in `packaging {}`, which is most of the difference
between a 12.7 MB and a 8.7 MB APK.

## Verification status

Run on a real device (Samsung, Android 14, 1440x3120) against
`../PaperView/tests/fixtures/sample.pdf`, the desktop build's render fixture:

- Opens from another app's `content://` URI; PDFium's native libs load and
  `FPDF` initialises.
- Continuous scroll renders correctly, fit-page sizing, page counter tracking.
- Search finds all 3 matches; highlights land exactly on the matched word.
- **Page 3 carries a CropBox that doesn't start at (0,0)** — it renders
  narrower than its neighbours, centres correctly among mixed page sizes, and
  its search highlight is still exact. That's the desktop build's old bug,
  correct here.
- Thumbnails render; the outline shows nested entries indented and unduplicated.

Fixed during that pass: the drawer's tab row was hidden under the status bar —
it sits outside the screen's own inset handling, so it needed its own.

**Tools: 16 instrumented tests, all passing on-device** (`connectedDebugAndroidTest`)
— merge, delete, split, reorder, rotate (asserting the recorded rotation),
watermark, page numbers, lock/unlock round-trip, password change, image→PDF,
compress, signature stamp, image export and range parsing. Plus a full
end-to-end pass of Number Pages through the UI on the **minified release**
build, which is what proves the R8 rules keep PDFBox's font machinery alive.

The redesign was checked on-device across Library, Tools and Settings in both
light and dark. One bug found and fixed: the Tools masthead drew under the
status bar, because each tab was applying the Scaffold's top inset itself and
one of them lost it — the shell now applies that inset once and hands the tabs
bottom padding only.

The camera scanner has produced a real scanned PDF that appears in the Library,
so that path works end to end.

Still untested at runtime: printing, the Backblaze round-trip, text selection
and its drag handles, single-page mode, and the "make default PDF app" flow.

**v1.3.0 on-device pass (30 instrumented tests green on an SM-S928B).** The
shelf fix was traced end to end: a `VIEW` intent carrying a one-shot grant, the
copy appearing in `files/shelf` at the source's exact size, a second open reusing
that same copy rather than duplicating it, and the stored Recent line keyed at
the copy's path —

    …151  0  fit_page  0  ShelfProof.pdf  /data/user/0/…/files/shelf/a687b6f4….pdf

— a path that resolves, which is the whole of what the readability filter asks.
The themed icon was rendered and looked at, before and after, at launcher size.

**v1.3.1 was verified against the reader's own data.** Their shelf held the
same PDF four times over, byte for byte, each duplicate with its own reading
position. The backup/restore/migrate cycle ran on the device: 8 copies became 4,
8 Recent rows became 4, and the notes came back at page 15 — the position
the duplicate bug had reset to zero. 36 instrumented tests and 35 JVM tests pass.

**The bars were then measured, not eyeballed.** With a document open, the frame
was captured with the bars shown and again with them hidden, and the band of
page visible in both states compared pixel by pixel: **0 of 2,692,800 pixels
differ**, and the full 4.5-megapixel screen is identical again after a
hide/show cycle. The page cannot move.

**The axis lock was measured the same way.** A drag of 300px vertically and
40px sideways displaced the page by exactly (0, 315): correlating the two
frames gives a perfect match at dx=0 against an error of 12 to 17 at every
neighbouring offset. A first attempt at this was thrown away as worthless —
the swipe had scrolled three pages at 2.8x zoom, so the frames shared no
content and the "best" offset was noise. A correlation is only evidence when
its minimum is sharp.

**The scanner pipeline was measured and then looked at.** A synthetic
photograph — real glyphs, light falling off across the page, a hard-edged
corner shadow — comes out with the paper's variation more than halved and its
mean above 242, with the text still there. The four filters were rendered on
the device and inspected side by side rather than trusted. A faint halo
survives where a shadow edge is perfectly sharp, which is inherent to a
blurred illumination estimate and is recorded as its own characterisation
test; real shadow edges are soft and do not produce it.

**Still not verified:** the pinch. `adb shell input` has no multi-touch, so no
amount of scripting reaches it — that one is the reader's call. `DragLockTest`
covers the pure decision logic on the JVM, but no test can tell you how a thumb
feels.

**Known and left alone:** opening the *same* document once through the picker
and once from a chat still makes two entries. They are genuinely different
sources — one live and editable, one a snapshot — and deduplicating across them
would mean digesting every picker-opened file too.

**Android gives a document opened from another app only one read.** That grant
can't be persisted and dies with the task, so such a document used to vanish from
the shelf at the next launch. `Shelf` keeps a private copy to work around it —
see the note above. A `file:///sdcard/...` URI is a separate matter: it fails with
`EACCES` unless all-files access is granted in Settings. That's by design — the
SAF path is the one apps actually use.

`pdfiumandroid` is pinned to **1.0.33**, not the current 2.x: 2.x requires
`compileSdk 37`, which needs a newer AGP and an SDK platform that isn't
installed here. 1.0.33 exposes the whole API this app needs (`findStart`,
`textPageGetCharIndexAtPos`, `textPageCountRects`, `getPageLinks`,
`getTableOfContents`). It ships Kotlin 2.2 metadata, which is why the Kotlin
plugin is 2.2.0 while [LocalityAndroid](../LocalityAndroid) is still on 2.0.21.
