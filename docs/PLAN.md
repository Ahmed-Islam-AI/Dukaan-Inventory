# Plan

Each milestone ends with: `./gradlew testDebugUnitTest lintDebug assembleDebug` green,
`docs/PROGRESS.md` + `CHANGELOG.md` updated, version bumped, boxes ticked here.

## M0 Foundation ✅
- [x] git repo, Gradle project (versions in `libs.versions.toml`), manual DI `AppContainer`
- [x] Room 3 schema v1 (all 19 tables) with exported schema
- [x] Theme tokens, bottom-nav shell, placeholder tabs
- [x] `Money` + tests, schema smoke test on JVM SQLite
- [x] Lint: HardcodedText, RtlHardcoded as errors
- [x] CI (GitHub Actions)
- [x] Docs set

## M1 Catalog + stock-in ✅
- [x] `/plan-eng-review` + `/plan-design-review` on the plan before starting
- [x] Design system in code: bundled IBM Plex Sans (variable) / Plex Mono / Noto Nastaliq Urdu (OFL licences in `assets/licenses`), full token set, Urdu typography switch
- [x] Components: MoneyText, qtyLabel, StockBadge, Tag, EmptyState, SectionLabel, ScreenHeader, RowDivider (LineRow + PaySheet → M2, ScanButton → M3, AttentionList → M5)
- [x] Screens: Onboarding, Items list (search, All/Low/Negative), Item detail (stock, IMEIs, history, correct stock, take unit out), Add/edit item (opening stock inline, IMEI rows, variants, decimals), Purchases list/detail/void, Add stock (purchase), More (Purchases, Check stock)
- [x] HideCostToggle on Items (plain toggle until M6 PIN, D-45)
- [x] Device settings via SharedPreferences (deviceId, invoicePrefix, hideCost) instead of DataStore (D-47)
- [x] Live-IMEI uniqueness via `liveSerial` unique index (D-35)
- [x] DAOs: LedgerDao (writes), CatalogDao (screen flows), AuditDao (invariants)
- [x] `businessDay` helper (D-36); qty input via `Qty` (D-39)
- [x] `LedgerService`: createItem + opening stock, updateItem, purchase (lots, serials, buy-back as new row, pending fill), voidPurchase, adjustQuantity (± with FIFO), removeSerial; all in one write transaction each
- [x] `InvariantChecker` (all 5 invariants + liveSerial), used by every ledger test and More → Check stock
- [x] Tests: 20 LedgerService (incl. rollback, buy-back, pending fill, void allowed/refused), 7 FIFO, 2 business day
- [ ] Language picker (English/Urdu) → moved to M6 with the Urdu translation (needs per-app locale)
- [ ] Category picker is a free-text field with find-or-create; a dropdown can come later if needed

## M2 Sell + receipts + data safety ✅ (client can start using real data from here)
- [x] `FifoAllocator` (pure) + exhaustive tests (done in M1)
- [x] `SaleMath`: line totals, invoice discount, tax after discount, half-up rounding (D-37) + tests
- [x] `LedgerService.sell`: snapshots, FIFO allocations, pendingQty, serial → SOLD, payment(SALE), credit needs a customer, paid ≤ total, invoice seq per prefix
- [x] Purchase fills pendingQty oldest-first (M1) — now exercised by real sales in tests
- [x] Sell/cart (search by name/barcode/IMEI, IMEI picker, steppers, typed qty/price, inline zero-stock note, discount), pay step (amount received, change, mode, customer for credit), raised Sell nav action
- [x] Cart is activity-scoped: survives leaving the Sell tab (D-50)
- [x] Receipt prefix asked on first sale and after restore; changeable from the Sell header
- [x] `ReceiptLayout` (pure, tested) → `ReceiptRenderer` (Canvas, StaticLayout, Urdu-safe) → PDF share via FileProvider, A4 via PrintManager (D-51)
- [x] Receipt screen (preview, Share PDF, Print A4, New sale) and Sales list (More → Sales, CREDIT/VOID tags)
- [x] Backup: VACUUM INTO tmp → integrity_check → atomic `Files.move` on background, at most hourly; Auto Backup rules include only the snapshot; restore-from-snapshot on fresh install
- [x] Export/import `.dukaan` (zip + manifest.properties): validate, integrity_check, keep `dukaan.db.before-import`, swap, new deviceId, prefix prompt, process restart
- [x] Seeded randomized invariant test (D-31): 3 seeds × 500 ops
- [x] Tests: 15 sale ledger, 5 SaleMath, 5 ReceiptLayout, 8 BackupManager, 3 randomized → 79 total
- [x] Device walkthrough on emulator (see PROGRESS.md)
- [ ] Auto-print after Charge (D-46) → moved to M3 with the thermal printer (D-52)
- [ ] `/design-review` on the emulator build: recommended next
- [x] **Schema v1 frozen.** From now on every schema change = version bump + Migration + migration test

## M3 Hardware ✅ (code done; real printer + real barcode still to test on a phone)
- [x] Thermal printing: own ESC/POS raster encoder (`GS v 0`, 24-row bands) + Bluetooth SPP, no third-party library (D-56)
- [x] Paired-printer picker (More → Receipt printer), 58/80 mm, auto-print toggle, test print
- [x] Permissions: `BLUETOOTH_CONNECT` (12+) at use time; legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` `maxSdkVersion=30`; no location
- [x] Receipt: "Print receipt" button, auto-print after a sale (D-46), named errors (permission, Bluetooth off, not paired, cannot connect) with Share PDF still available
- [x] CameraX + ML Kit (bundled) scanner screen; single and continuous (bulk IMEI) modes; beep per new code; camera denied → "Type instead"
- [x] Scan in Sell (exact match goes straight to the cart), Items search, Add stock (IMEIs per phone line), Add item (opening IMEIs, barcode)
- [x] `ScanCodes.clean`: strips "IMEI:" labels/spaces, Luhn check (D-57)
- [x] Tests: EscPos (5), ScanCodes (3) → 87 total
- [ ] Manual test on the client's printer (58 mm text, Urdu name, 80 mm) — needs the device
- [ ] Manual scan test on a real phone (emulator AVD has no camera)

## M4 Money flows
- [x] Parties: customers/suppliers, balances, statement, record payment (+ cancel), WhatsApp reminder (share intent text)
- [x] Sale void (refused when returns exist)
- [x] Partial returns with pro-rata refund, credit-first, cost reversal; return void (D-58)
- [x] Expenses (soft delete, D-59)
- [x] Tests: void reversal, return ordering (pending first, newest lot), pro-rata rounding, credit math, return void, random ops → 98 total

## M5 Insight
- [x] Metrics exactly as ARCHITECTURE.md § Business metrics (D-29, D-61), one `ReportDao` + `Figures`
- [x] Home dashboard (today/month, fix-it lists, recent sales, backup reminder, first-run checklist)
- [x] Reports with date range; CSV export via SAF (blocked while cost is hidden)
- [x] Low-stock alerts (dashboard card + items filter)
- [x] Tests: MetricsTest (5) → 103 total

## M6 Hardening + release
- [x] App lock: PIN (salted PBKDF2 hash in device prefs, D-62) + BiometricPrompt; lock on background after N minutes
- [x] Urdu `values-ur/strings.xml` (needs native review), language picker, RTL pass at 1.3× font, Nastaliq font
- [x] Crashlytics (conditional on google-services.json), no PII
- [x] App icon · [ ] final name/applicationId (owner decision)
- [x] Privacy policy draft, Data safety answers, store listing draft (PRIVACY.md, RELEASE.md) · [ ] host the policy (owner)
- [x] Release signing config + R8 release smoke test · [ ] upload key, Play account, closed testing with the client (owner)

## M7 Accounts, staff, cloud (docs/ACCOUNTS.md)
### Phase 1: accounts + cloud copy
- [x] Animations (D-69): screen fade/settle, press feedback, count-up money, list item placement, banner pop
- [x] Firebase (Spark): Auth + Firestore, conditional on google-services.json (local-only mode without it)
- [x] Sign up / log in: phone + password, email + password, Google (Credential Manager)
- [x] Welcome, credentials, shop details (pre-filled from existing data), blocked, connect screens; More → Account
- [x] Account checks: verify on start/return (6 h), 14-day offline grace, block only on explicit signals
- [x] Cloud copy: gzip chunks in Firestore; upload on sign-up and after snapshots; restore on login; log out uploads then wipes
- [x] `firestore.rules`; `tools/reset-password` (Admin SDK on the owner's PC)
- [x] Tests: PhoneNumbers, CloudCopy round trip → 109; emulator walkthrough against the real project
- [ ] Real phone: Google sign-in, block a test account from the console
### Phase 2: staff
- [x] More → Staff (owner): add by phone or email, waiting invites, cancel, remove
- [x] Invited person signs up → "Join <shop> as staff?" → starts from the shop's cloud copy (or creates their own shop instead)
- [x] Staff limits: Staff and Backup & restore are owner-only; staff keep their own cloud copy
- [x] Removed staff: next check wipes their phone and says "You were removed from <shop>"
- [x] Rules: invites, staff `users` docs, owner reads/removes staff, per-staff copies; emulator walkthrough
- [ ] Two phones: removed while signed in → wiped on the next check
### Phase 3: live sync (docs/SYNC.md)
- [x] Stock derived from facts by one replay (StockReplay/StockRebuild); LedgerService writes facts (D-75)
- [x] Ids from meaning for rows two phones can both write (D-76)
- [x] Schema v2: sync outbox (triggers) + sync state; migration + test (D-77)
- [x] Change log in Firestore: push one gzipped document per batch, live listener, newest edit wins
- [x] Clashes listed for the owner: Home → Problems between phones (D-78); bill letter unique per phone
- [x] Staff start from the shop copy + sync (D-79); log out pushes first; Account shows sync state
- [x] Tests: two-phone merges, 3-phone random convergence, migration → 116
- [ ] Two real phones: live sale appears on the other phone; IMEI sold on both while offline

## Invoice templates (D-80)
- [x] A4 invoice templates Modern / Classic / Minimal (vector PDF, multi-page, VOID watermark)
- [x] Slip templates Classic / Compact / Bold (logo, tax number, terms)
- [x] More → Invoice design (owner): templates, colour, logo, address, phone, tax no., terms, footer; live preview
- [x] Receipt screen: Invoice / Slip preview; Share sends what is shown; A4 prints the invoice
- [x] Schema v3 + migration test; slip template tests → 120
- [ ] Real printer: Bold / Compact slip and the logo on the client's thermal printer; a 3-page bill on paper

## Categories, two prices, pick-to-sell (D-85, D-86)
- [x] Category required (none = "Other"); purchase + sale price with profit strip; schema v4 + migration test
- [x] Category → item picker for Sell (add and keep picking) and Add stock (+ new category / item)
- [x] Barcode first, per-unit serial optional; "IMEI" wording only for mobile shops
- [x] Design pass: saffron Add buttons, bold titles, letter tiles, Items category chips, Reports chip row → 124 tests
- [ ] Real phone: scan a barcode in Sell and Add stock

## Error handling (named; no catch-all)
| Codepath | Failure | Handling | User sees |
|---|---|---|---|
| Sale/purchase txn | `SQLiteException` constraint (live dup IMEI, dup invoice no.) | rollback | "This IMEI is already in stock" field error |
| Sale | serial status != IN_STOCK | abort before write | "This unit is already sold" |
| Purchase void | lot consumed / serial sold | refuse | "Items from this purchase were sold. Use stock adjustment." |
| Thermal print | `BLUETOOTH_CONNECT` denied | stop; sale stays saved | rationale + "Share PDF instead" |
| Thermal print | Bluetooth off / not paired / socket `IOException` | stop; sale stays saved | "Printer not connected" + Retry + Share PDF |
| PDF write | `IOException` | abort share | "Couldn't create receipt" + Retry |
| Scanner | camera permission denied | manual entry | rationale + "Type instead" |
| Import | wrong file / newer schema / corrupt / integrity_check fails | validate first, keep old DB copy, then swap | specific message per case |
| After restore/import | cloned deviceId, reused prefix | regenerate deviceId, prompt for prefix | "Choose a receipt prefix for this phone" |
| Room migration | missing migration | build/test fails; never destructive fallback | n/a |

## Engineering review additions (2026-10-15), already applied to schema v1 in M0
- [x] Bundled SQLite driver in production (D-32)
- [x] Schema: lot sources + lot_allocation, liveSerial, businessDay/voidedAt, taxRateBp snapshot, payment kind/reversal link, return status, milli-unit quantities (D-33..D-39)
- [x] `Qty` value class + tests; shared Urdu/Arabic digit normalization
- [x] `Money.format`: lakh grouping, optional symbol (D-42, D-43)
- [x] M4: return void (D-38); sale void refused while an ACTIVE return exists
