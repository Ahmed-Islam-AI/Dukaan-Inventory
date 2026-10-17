# Dukaan: agent guide

Offline-first Android inventory + billing app for any small shop (first client: a mobile-phone
shop in Pakistan). Kotlin, Jetpack Compose, Room 3. Personal project of the owner, not Moonlabor.

**Before any change, read:** `docs/PROGRESS.md` (where we are) → `docs/PLAN.md` (what's next) →
`docs/ARCHITECTURE.md` (how it works) → `docs/DECISIONS.md` (why; do not silently reverse a decision).

## Commands
```bash
./gradlew testDebugUnitTest            # JVM tests (Room on bundled SQLite, no emulator)
./gradlew lintDebug                    # lint; HardcodedText/RtlHardcoded are errors
./gradlew assembleDebug                # debug APK
./gradlew installDebug                 # to a connected device/emulator
./gradlew connectedDebugAndroidTest    # on-device tests
```
Gate before calling a milestone done: `testDebugUnitTest lintDebug assembleDebug` all green.
Add `--offline` when the Gradle cache already has everything (faster, works without network).

## Stack (versions pinned in `gradle/libs.versions.toml`; never inline versions)
AGP 9.4 (built-in Kotlin: do NOT apply `org.jetbrains.kotlin.android`) · Gradle 9.6 · Kotlin 2.4.10 ·
KSP 2.3.10 · Compose BOM 2026.08 · Room **3** (`androidx.room3.*` imports, explicit SQLite driver) ·
Navigation-Compose 2.10 · compileSdk 37 / targetSdk 36 / minSdk 26 · JDK 17+ (25 verified).

## Hard rules
1. **Money is `Long` minor units** (`...Paisa`, `Money`); **quantity is `Long` milli-units**
   (`...Milli`, `Qty`: 2.5 kg = 2500). Never Float/Double for either.
2. **Never hard-delete.** Soft delete (`deletedAt`) only for master data (category, product,
   variant, party). Ledger rows are never deleted: voids = status VOIDED + reversal rows.
3. **Every stock change goes through LedgerService** in one Room transaction: it writes facts, then
   `StockRebuild` derives lots/allocations/costs/IMEI status/`onHandMilli` (D-75). Never write a
   derived column anywhere else, and never bump `updatedAt` for a derived write (sync, docs/SYNC.md).
4. **IDs are UUID strings** generated on device; every row embeds `SyncMeta` (sync-ready, D-13).
5. **All UI text in `strings.xml`.** Use start/end, never left/right (Urdu RTL, D-08).
6. **Sale lines snapshot name/price/cost.** Old receipts never change when items are edited.
7. **No destructive migrations.** Schema v1 may change in place until the client has real data
   (M2). After that: bump version, add a `Migration`, add a migration test.
8. No SQL foreign keys (rows may arrive out of order after sync); integrity = LedgerService +
   invariant tests (ARCHITECTURE.md "Invariants").

## Layout
```
app/src/main/java/app/dukaan/android/
  DukaanApp.kt, AppContainer.kt (manual DI, D-23), MainActivity.kt, NavGraph.kt
  core/db        Room entities + DAOs + DukaanDatabase
  core/money     Money value class
  core/ui/theme  tokens (mirror docs/DESIGN.md)
  core/{ledger,receipt,printer,scan,backup,lock}   from M1-M6
  core/{account,sync}   accounts, cloud copy, live sync (M7)
  feature/{onboarding,home,sell,items,purchase,parties,returns,expenses,reports,settings}
app/schemas/     exported Room schemas: COMMIT these
```
Pattern per screen: `XScreen(viewModel)` + `XViewModel(repos)` exposing `StateFlow<XUiState>`,
built in `NavGraph` with `viewModelFactory { initializer { ... container.repo ... } }`.

## Accounts (M7, docs/ACCOUNTS.md)
- `app/google-services.json` (gitignored) switches on Firebase; without it the app runs local-only
  (`AccountState.LOCAL`). Security rules: `firestore.rules`, published by pasting in the console.
- Never wipe a phone on an ambiguous server answer: block only on explicit signals (D-72).
- Sync: `updatedAt` must change exactly when a fact changes (triggers queue those rows; newest wins).
  Rows two phones can write for the same action need ids from `nameId(...)` (D-76). New table →
  add it to `SyncTables.DERIVED` and to `StockRebuild` if it affects stock.
- Every DukaanDatabase is built through `SyncTables.configure` (driver, migrations, triggers).
- Test against the real project with throwaway accounts and list them in PROGRESS.md for deletion.

## Known traps
- Room 3 `@Upsert` uses exception control flow that breaks in JVM tests: use `@Insert(onConflict = REPLACE)`.
- JVM tests need `sqlite-bundled-jvm` (not `sqlite-bundled`) or you get UnsatisfiedLinkError.
- `Math.absExact` and other Java 15+ `Math` methods need API 33; minSdk is 26.
- Production uses `BundledSQLiteDriver` (D-32). Do not switch to the system driver: Android 8-10
  lack `VACUUM INTO` and tests would no longer match devices.
- No raw-SQL indexes: Room migration validation rejects undeclared ones. Use columns like `liveSerial`.
- Report days come from stored `businessDay`, never from timestamps at query time.
- No material-icons dependency: icons are vector drawables in `res/drawable` (Material Icons path data, Apache 2.0); build screens from `core/ui/Kit.kt` (D-67: icon first, short labels).
- Emulator: AVD `Dukaan_API36` (hand-made in `~/.android/avd`, no avdmanager installed). Start with
  `emulator -avd Dukaan_API36 -no-snapshot`. If an "Try out your stylus" sheet eats taps, run
  `adb shell settings put secure stylus_handwriting_enabled 0`.
- Driving the emulator from Git Bash: `export MSYS_NO_PATHCONV=1` before `adb shell ... /sdcard/...`
  (otherwise paths get rewritten to `C:/Program Files/Git/...`). Tap by text via `uiautomator dump`,
  not fixed coordinates: the keyboard moves layouts.
- The hand-made AVD has no camera image and no Bluetooth: scanning and printing need a real phone.
- Hardware-GPU emulator screenshots can freeze on a stale frame; start with `-gpu swiftshader_indirect`.
- Material 3 defaults leak lavender (`secondaryContainer`) and the system font (unset type slots):
  both are set explicitly in `Theme.kt`; keep it that way when adding components.

## Keeping docs current
At the end of every milestone update **only** `docs/PROGRESS.md` and `CHANGELOG.md` (plus tick
`docs/PLAN.md`). Touch other docs only when their subject changed. New decision → add to
`docs/DECISIONS.md`. New idea out of scope → `docs/FUTURE_FEATURES.md`.

## Open (owner decides; not blocking M1-M5)
Final app name + applicationId (permanent after first Play upload) · Play developer account type ·
client's thermal printer model.
