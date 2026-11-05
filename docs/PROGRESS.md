# Progress

**Current milestone:** Categories + two prices + design pass done (after M7 Accounts)
**Version:** 0.16.0
**Build:** ✅ `testDebugUnitTest lintDebug assembleDebug` green (124/124 tests), 2026-10-16

## Next up
1. Owner decisions (RELEASE.md § Before the first Play upload): final name + applicationId, Play
   developer account, upload key, Firebase project (optional, for Crashlytics), privacy policy URL.
2. Device tests on a real phone: the client's Bluetooth printer (incl. an Urdu item name), camera
   scanning, fingerprint unlock, TalkBack on the floating "Add …" buttons.
3. A native Urdu speaker reviews `values-ur/strings.xml` (machine-drafted; see M6 log).
4. Play closed test with the client (12 testers × 14 days on a personal account).

## Blocked / waiting on owner
- Final app name + applicationId (needed before first Play upload, not before).
- Google Play developer account (personal: plan a 12-tester closed test of 14 days; start by M3).
- Client's thermal printer (model + a day of access) for the M3 print test.

## Log
### 2026-10-16: categories, two prices, pick-to-sell, design pass (0.16.0, D-85, D-86)
- Owner: categories when adding stock; purchase + sale price; Sell picks category then item;
  barcode instead of IMEI (generic shops); professional look on every screen except landing/login.
- Built: schema v4 + MIGRATION_3_4 (backfill from newest batch), PickDao, ItemPickerSheet (Sell and
  Add stock), quick "new item" from Add stock, item form (required category chooser, two prices,
  profit strip, barcode, optional serial switch), serial label per shop type, Items category chips
  and profit hints, item detail profit row, saffron FABs, bold titles, Reports chip row.
- Tests: PickTest (categories/Other, barcode search, price pre-fill + FIFO cost), migration v3 → v4.
- Verified on the emulator: Sell picker → cart, new category "Drinks", Coke (80/100, barcode) and
  Pepsi via Add stock, barcode search, Items chips, item detail, Reports. Camera scan untested
  (no emulator camera).
- Test data on account 03009990031: category Drinks, items Coke 500ml and Pepsi 500ml, one purchase.

### 2026-10-15: landing, login and sign-up redesign (0.15.0, D-84)
- Owner: "make login and sign up more professional and a new look; a landing page as the first page".
- Built: branded launch window (values / values-v31 themes, ic_splash, splash_window; MainActivity
  swaps to the page colour), AuthLook.kt (brandSurface, AuthHeader, AuthSheet, MethodSwitch, Landing
  carousel with floating illustration and dots), Credentials / ShopDetails / JoinShop on the new
  header + sheet.
- Verified on the emulator: launch recording (saffron splash → landing, logo pop, sheet rise),
  carousel auto-advance and swipe, login, sign-up with the Email side, Step 2 of 2 shop details.
- Test account to delete: dukaan.test7@example.com (sign-up stopped at shop details).

### 2026-10-15: Home refresh (0.14.0, D-83)
- Owner: remove Welcome's backup button; make Home more professional and animated.
- Built: Header (shop badge, greeting, date), MoneyCard (decorative depth, sliding Today/Month,
  payment pills, stat icons, margin), WeekChart (ReportDao.salesByDay, growing bars, today in brand
  colour), section titles, `Reveal` staggered entrance (core/ui/Motion), whole-rupee count-up.
- Verified on the emulator: screenshots of the new Home with real sales, and a screen recording
  (ffmpeg frame sheet) showing the cascade, count-up and bar growth. Lint caught a non-observed
  locale in the chart labels (fixed with LocalConfiguration).

### 2026-10-15: new account = new empty shop (0.13.2, D-82)
- Owner: no keep/new choice; any number of accounts; a new account removes the phone's old shop data.
- Built: sign-up clears tables, snapshot, receipt PDFs and the copy temp file after the account exists;
  form starts empty; Welcome: "removes it from this phone" + "Save a backup file first".
- Verified on the emulator: backup file saved from Welcome; new email → empty "Bilal Traders", no old
  receipts in cache. Client path: save backup → sign up → More → Backup → import (pending a real run).
- Test account to delete: dukaan.test6@example.com.

### 2026-10-15: sign-up took over the phone's shop (0.13.1, D-81)
- Owner report: created an account with email, landed in "Ali Mobiles" instead of the new shop. Cause:
  finishSignUp only created the typed shop when the phone had none, so any local shop (here the
  emulator's test shop) was silently adopted, and the cloud shop name and phone disagreed.
- Fix: a keep / start-new choice on shop details, a confirmation before removing, keep applies the
  typed details. Verified on the emulator: new email + start new → empty "Bilal Traders"; new email
  + keep + rename → Ali Mobiles' data as "Ali Mobiles Lahore".
- Owner's account test@dukaan.com still holds a copy of the test shop (signed up before the fix).
- Test accounts to delete: dukaan.test4@example.com, dukaan.test5@example.com.

### 2026-10-15: invoice templates (D-80)
- Owner: "the invoice print is very simple; add templates the user can customize".
- Built: InvoicePage (A4 Modern / Classic / Minimal, vector PDF pages, multi-page, VOID watermark),
  slip templates Classic / Compact / Bold, InvoiceDesign (logo encode/decode), More → Invoice design
  with live preview, receipt screen Invoice / Slip chips, schema v3 (design on the shop row, synced)
  with migration + test. Share now sends an A4 PDF invoice; A4 prints real pages, not a scaled slip.
- Verified on the emulator: all three A4 templates and the Bold slip with real shop details, a logo
  picked through the photo picker, blue brand colour; a real bill as invoice; Share builds the PDF;
  A4 opens Android's print preview with the invoice.
- Found and fixed: A4 print crashed ("can print only from an activity"): the language context
  handed PrintManager a non-Activity context (pre-existing; MainActivity now serves the print
  service from Android's own base). Classic terms box overhung the table edge.
- Not yet verified: a bill long enough for 2+ pages, slips on the client's thermal printer.

### 2026-10-15: M7 phase 3, live sync
- Built (docs/SYNC.md): stock derived from facts by one replay (StockReplay/StockRebuild, D-75) with
  LedgerService writing facts only; ids from meaning (D-76); schema v2 with trigger-filled outbox and
  sync state + migration (D-77); Firestore change log, one gzipped document per push, live listener;
  owner's "Problems between phones" (D-78); bill letter unique per phone; staff start from the shop
  copy + sync, staff copies retired (D-79); Account shows sync state. FifoAllocator removed (replay
  does FIFO). Tests 116: two-phone merges, 3-phone random convergence (34 seeds checked), migration.
- Found by the random test and fixed before release: a bill voided on one phone could take stock again
  when a return of it was voided later on another phone; two returns of the same items on two phones
  plus a return void double-counted (fix: lines settle to "sold − active returns"); stock taken out on
  two phones beyond what existed had no clash (added).
- Verified on the emulator against the real project (rules re-published by the owner): existing shop
  seeds 51 rows and uploads → owner sale B-0004 pushed in seconds → staff invited, owner logs out →
  staff joins, has B-0004, IMEI already sold is not offered; Sell refuses letter B and suggests C →
  staff sale C-0001 pushed → staff logs out → owner logs in: copy (4 bills) + live pull of C-0001 =
  5 bills, both IMEIs sold, no clashes. Existing v1 database migrated on the device.
- Found and fixed live: after log out Firebase's sign-out was lost to the immediate restart, so the
  phone signed itself back in (now a saved "sign-out pending" marker signs out again on start).
- Not yet verified: two real phones online at the same time (live listener between running phones),
  an IMEI sold on both while offline (covered by tests), Google sign-in, console block.
- Test accounts to delete: phones 0300 9990031 (Sync Owner), 0300 9990032 (Sync Staff) and their shop's
  `changes`, plus the earlier ones listed in RELEASE.md.

### 2026-10-15: M7 phase 2, staff
- Built: invites + staff join (D-73), More → Staff, staff copies (D-74), removed-staff wipe with its own
  message, owner-only Staff and Backup, rules for invites/staff/copies (owner published them).
- Verified on the emulator against the real project: owner sign-up → invite saved and listed → same
  invite refused → invited phone signs up, sees "Join Ali Mobiles as staff?", joins and gets the shop →
  staff menus hide Staff/Backup → staff log out uploads their copy → owner sees and removes them →
  removed staff logging in again is offered "create your own shop".
- Found and fixed: adding staff failed (pre-read of the invite denied by the rules; now write-only).
- Not yet verified: removed while signed in (two phones), plus phase 1's Google sign-in and console block.

### 2026-10-15: M7 phase 1, accounts + cloud copy + animations
- Owner Q&A fixed the scope (docs/ACCOUNTS.md); Firebase project `dukaan-inventory` created by the owner
  (Spark, Firestore asia-south1, Email/Password + Google enabled, rules published).
- Built: motion (D-69), AccountRepository/AccountManager/CloudCopy/PhoneNumbers, auth screens, account gate,
  More → Account, firestore.rules, tools/reset-password. Tests 109 (+3).
- Verified on the emulator against the real project: existing shop signs up by phone and uploads →
  log out wipes the phone → wrong password refused → log in on the empty phone restores the whole shop →
  forgot password on a phone account without email explains why → new email shop → Google with no
  Google account on the phone shows a clear message.
- Found and fixed while testing: false "blocked" after a restore wiped the phone (D-72); Firestore disk
  cache kept data after log out; stale phone pre-fill on email sign-up.
- Not yet verified: real Google sign-in (needs a phone signed in to Google), blocking from the console.
- Test accounts left in the project for the owner to delete: phone 0300 9990001, dukaan.test2@example.com.

### 2026-10-15: UI polish, "more professional and user friendly" (D-68)
- Confirmations (LocalNotify, top pill) on 11 actions; brand money card; "Sale saved" banner; next bill
  number in Sell; FormCard / SearchField / ChoiceTile in Kit; item, purchase, payment and onboarding forms
  in titled cards; onboarding welcome header + shop-type tiles; Search clear button.
- Found on emulator and fixed: the confirmation pill covered floating "Add" buttons at the bottom; moved
  to the top of the screen.
- Onboarding checked by clearing the emulator app, then restoring its data from a copy.

### 2026-10-15: UI refresh after owner review (D-67)
- Owner: "more text instead of icons, not easy for a non-technical person; the reference app is
  self-explanatory". Audited every screen on the emulator, then: icon set (32 Material icons),
  shared Kit (IconBadge, ActionTile/ActionBar, MenuRow, CardRow, Avatar, ModePicker, ButtonLabel),
  Home quick actions, icon menus in More, picture tiles on receipt/party/item, card lists everywhere,
  payment-mode tiles, big-icon empty states, 30 strings shortened in both languages, Call action.
- Verified on emulator in English and Urdu (RTL mirrors, icons flip where directional).
  Gate green (106 tests, lint 1 known warning).

### 2026-10-15: M6 Hardening (part 1) done
- App lock (`core/lock`): PIN hashed with PBKDF2 + salt in device prefs, backoff after 5 wrong tries,
  BiometricPrompt (fingerprint or phone screen lock) for unlock and forgotten PIN, lock-after setting,
  PIN to re-show hidden cost (D-45). MainActivity is now a FragmentActivity (BiometricPrompt).
- Languages: in-app picker (phone / English / Urdu), restart on change, `locales_config` for Android 13+.
  Urdu strings drafted by an AI agent and checked by script (same names, format args, plurals, Latin
  digits); 2 ambiguous words fixed by hand (Yesterday vs Total both "کل", Gross profit). **Needs a native
  speaker's review before release.**
- Icon, conditional Crashlytics, release signing config, PRIVACY.md, Data safety + listing in RELEASE.md.
- Verified on emulator: set PIN → lock immediately → leave and return → lock screen → wrong PIN refused →
  right PIN back on the same screen → hide cost → showing it asks for the PIN. Urdu at 1.3× font:
  Home, Sell, Parties, Reports mirror correctly; fixed Nastaliq digits in amounts. Release (R8) build:
  lock, Home, Parties, Reports, receipt + PDF share with no crash. Emulator returned to English, no PIN.
- Lint found a real release bug: Play's per-language split would have dropped Urdu from English phones.
- Not done (owner): final name/applicationId, Play account, upload key, Firebase project, hosted policy.

### 2026-10-15: M5 Insight done
- `ReportDao` + `Figures` (core/report): one definition of sales, cost, profit, expenses and money in − out
  for Home, Reports and the CSV. D-61: a voided bill leaves its sale day; returns count on the return day.
- Home (start tab, mockup 2), Reports with date presets + date-range picker, CSV export via the system
  file picker, backup reminder (new device setting: last export time), Home → filtered Items.
- Verified on emulator: Home showed −Rs 35,000 today (a Rs 37,000 supplier payment + the M4 void and refund,
  all real cash movements) → sold a phone at Rs 42,000 → Rs 7,000, profit 4,000 → hide-cost hides profit on Home
  and Reports and disables CSV → Reports for this month: sales 42,000, cost 38,000, expenses 25,000, net −21,000
  → CSV saved to Downloads and read back → charger sold at zero stock → "waiting for its cost" and "Charger 25W is
  at −2" rows → Fix / Add stock / View (opens Items on the Low filter) all land right.
- Walkthrough fixes: hero renamed "Money in − out" (it nets supplier payments), zero profit no longer green,
  CSV blocked while cost is hidden, whole-row switches on the item form.
- Open: the extended floating buttons ("Add item", "Add customer", …) don't appear in the emulator's
  accessibility dump although they work by touch. Check with TalkBack on a real phone before release.

### 2026-10-15: M4 Money flows done
- Ledger: sale void, partial returns (pending first, newest allocation first, cumulative-rounded refund,
  credit first), return void, party payments + payment void, parties, expenses. Invariant 6 added.
- Decisions D-58 (return void re-takes stock at today's FIFO cost), D-59 (expenses soft-deletable),
  D-60 (refund payments link to the return, not the sale; a sale line's cost is always net of returns).
- Screens: Parties tab (now in the bottom bar), party page with history/payments/reminder, Return items
  and Void bill on receipts, Expenses under More.
- Verified on emulator with the existing data: Ahmed Raza owed Rs 5,000 → received Rs 2,000 → Rs 3,000 →
  returned the phone (Rs 40,000): app said "Rs 3,000 taken off Ahmed Raza's balance. Give Rs 37,000 back in
  cash" → cancelled the return → Rs 3,000 again → voided A-0001 → Ahmed shows Rs 2,000 paid in advance →
  expense Rs 25,000 rent → Check stock OK.
- Device walkthrough found and fixed 1 issue: a voided receipt said "Balance due" for the whole total.
- Not built (later): a receipt for the return itself, and showing returns on the original receipt
  (FUTURE_FEATURES.md).

### 2026-10-15: M3 Hardware done (code); device tests pending
- Printing: own ESC/POS raster encoder + Bluetooth SPP (D-56), printer settings, Print button, auto-print.
- Scanning: CameraX + bundled ML Kit, single and continuous (bulk IMEI) modes, wired into Sell, Items,
  Add stock, Add item; `ScanCodes.clean` with Luhn (D-57).
- Verified on emulator: camera permission prompt, scanner screen + CameraX pipeline running (AVD has no camera
  image), printer screen, Bluetooth permission, print error path ("no longer paired") with the sale untouched.
- NOT verified: printing on a real printer, scanning a real barcode. Both need physical devices.

### 2026-10-15: /design-review on the emulator build
- 7 findings, 5 fixed and verified, 2 polish deferred. Design score B → A−, AI slop A.
- Fixed: back arrow moved to the header start (9 screens), lavender nav label, developer placeholder tabs
  hidden (bar shows Items · Sell · More until Home/Parties exist), "Sale price" label, supplier on purchase detail.
- Deferred: TalkBack check of the floating "Add item" label; typographic minus "−" for negatives.
- Report: ~/.gstack/projects/inventory_management_app/designs/design-audit-20261015/

### 2026-10-15: M2 Sell + receipts + data safety done
- Selling with FIFO cost, pending cost, IMEI → SOLD, credit, discount, tax; receipts as PDF/A4; sales list.
- Backup: snapshot on leaving the app, Auto Backup rules, restore on fresh install, export/import file.
- Verified on emulator: sell phone with IMEI → Rs 2,000 discount → Rs 35,000 received + Rs 5,000 on Ahmed Raza's
  balance → receipt A-0001 matches DESIGN.md → Share PDF sheet → A4 preview → Sales list (CREDIT) → Check stock OK →
  export `.dukaan` (7 KB) → second sale A-0002 → restore → back to A-0001 only, prefix prompt, `before-import` copy kept.
- Device walkthrough found and fixed 5 issues: discount dialog appended to a leftover "0" (2000 → 20000),
  prefix dialog same, no way to change the prefix later, tinted pill behind the raised Sell button, cart lost on Back.
- Moved: auto-print → M3 (D-52). Schema v1 frozen.

### 2026-10-15: M1 Catalog + stock-in done
- LedgerService (create item + opening stock, purchase, void purchase, ± adjust, take unit out), FIFO allocator,
  invariant checker; 43 JVM tests incl. rollback, IMEI buy-back, pending-cost fill.
- Screens: onboarding, items list/detail/add/edit, add stock, purchases list/detail/void, More with Check stock.
- Design system in code: tokens, bundled fonts, components.
- Decisions D-47..D-49 (SharedPreferences for device settings, variable Plex font, free-text category).
- Not done in M1, moved: language picker → M6.
- Verified on emulator (Android 16, AVD `Dukaan_API36`): onboarding → add phone with 2 IMEIs → add stock →
  duplicate IMEI rejected → corrected → 3 units + history → Check stock passes.
- Device walkthrough found and fixed 8 issues: lavender selection colour, system font in dialogs, counts
  wrong during search, duplicate Add-item action, "Default" variant label, ✕ instead of back arrow,
  Add stock not preselecting the item, IMEI items not prefilling last cost.

### 2026-10-15: Design review done, DESIGN.md v1
- Score 5/10 → 9/10. HTML mockups (Sell, Home, Items, Urdu Home) approved as visual reference.
- Decisions D-40..D-46: hero = collection, IBM Plex Sans, lakh grouping, symbol on totals only,
  Latin digits in Urdu, hide-cost toggle, auto-print.
- `Money.format` now does lakh grouping and symbol-less amounts.

### 2026-10-15: Engineering review done, schema v1 hardened
- 14 findings (6 review + 8 outside voice), all accepted and applied: D-27..D-39.
- Schema: lot sources + `lot_allocation`, IMEI buy-back as new row with `liveSerial` unique index,
  `businessDay`/`voidedAt`, tax snapshot, payment kind + reversal link, return void, milli-unit quantities.
- Production now ships bundled SQLite (Android 8-10 lack VACUUM INTO).
- New: `Qty` value class; Urdu/Arabic digit input for money and quantities. Tests 7 → 13.

### 2026-10-14: M0 Foundation done
- Plan approved after CEO review + outside-voice review (22 decisions, see DECISIONS.md).
- Project skeleton builds on AGP 9.4 / Gradle 9.6 / JDK 25; Room 3 schema v1 exported (19 tables).
- Tests: MoneyTest (5), SchemaSmokeTest (2). Lint 0 errors; 3 expected warnings
  (DataExtractionRules → M2, MissingApplicationIcon → M6, OldTargetApi → deliberate).
- Deviation from plan: manual DI instead of Hilt (D-23).
