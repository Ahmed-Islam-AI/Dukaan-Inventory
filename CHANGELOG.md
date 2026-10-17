# Changelog

All notable changes to this project. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning: [SemVer](https://semver.org/) (`versionName` in `app/build.gradle.kts`).

## [Unreleased]

## [0.16.0] - 2026-10-16 (categories, two prices, pick-to-sell, design pass)
### Added
- Every item has a category (required; older items show under "Other"). Categories can be made while adding an item or stock.
- Two prices per item: purchase price and sale price, with a live "Profit per unit Rs X (Y%)" strip. Add stock pre-fills the purchase price (editable); profit still uses what each batch really cost (FIFO). Schema v4 (`variant.purchasePricePaisa`, filled from each item's newest batch).
- Sell opens a picker: category tiles, then items; tap to add to the cart and keep picking. Search and Scan stay; a scanned barcode adds its item straight away.
- Add stock uses the same picker, with "+ New category" and "+ New item" (name, both prices, barcode).
- Items list: category chips, profit per unit on each row; item detail shows purchase price and profit.
### Changed
- Barcode replaces IMEI as the main code on the item form; tracking each unit by serial/IMEI is an optional switch (off by default). Receipts and screens say "IMEI" only for mobile shops, else "S/N".
- Design pass: saffron "Add" buttons, bold screen titles, letter tiles instead of circles, Reports date chips on one scrolling row.

## [0.15.0] - 2026-10-15 (new landing, login and sign-up)
### Changed
- Launch: a saffron splash with the store mark that flows straight into the app (no white flash).
- New landing page: brand row, a swipeable feature carousel (bills, stock, udhaar, sync) that moves on by itself with a floating illustration and page dots, and "Get started" / "I already have an account" on a sheet that rises from the bottom.
- Log in and sign up: saffron header ("Welcome back" / "Create your account"), the form on a white sheet, a sliding Phone / Email switch, rounded fields, Google under "or", and the other way in at the bottom. Sign-up shows "Step 1 of 2" / "Step 2 of 2"; the shop details and join screens use the same header.

## [0.14.0] - 2026-10-15 (Home refresh)
### Changed
- Home: greeting header with the shop's badge; the money card has a sliding Today / Month switch, payment pills, icons on Sales · Profit · Bills and the profit margin; new "Last 7 days" sales chart; section titles.
- Home animates in: sections rise and fade in one after another, amounts count up in whole rupees, chart bars grow, recent bills slide in (first open only).
- Welcome no longer shows "Save a backup file first".

## [0.13.2] - 2026-10-15
### Changed
- A new account always starts with its own empty shop: any shop data still on the phone (and its cached receipts and snapshot) is removed when the account is created. The keep-or-start-new choice from 0.13.1 is gone.
- Welcome says when a shop is still on the phone and offers "Save a backup file first" (import it later from More → Backup).

## [0.13.1] - 2026-10-15
### Fixed
- Creating an account on a phone that already had a shop always took over that shop's data, even when a different shop name was typed. Sign-up now asks: keep this shop's data (it takes the details just typed) or start a new, empty shop (after a warning that the old shop will be deleted).

## [0.13.0] - 2026-10-15 (invoice templates)
### Added
- Three A4 invoice templates (Modern, Classic, Minimal) and three printer slips (Classic, Compact, Bold).
- More → Invoice design (owner): pick templates, brand colour, logo, address, phone, tax number, terms or warranty, and the thank-you line, with a live preview. All of the shop's phones use the same design.
- Receipt screen: switch between Invoice and Slip; Share sends the one shown (the invoice as an A4 PDF).
- Invoices show PAID / DUE / VOID, IMEIs under items, a signature line, and continue on more pages for long bills.
### Changed
- A4 printing prints the invoice as real A4 pages instead of an enlarged slip.
- Database version 3 (existing shops upgrade automatically).
### Fixed
- A4 printing could crash with "Can print only from an activity".

## [0.12.0] - 2026-10-15 (M7 phase 3: live sync)
### Added
- Live sync: sales, stock, customers, payments and expenses made on one phone appear on the shop's other phones within seconds; work done offline merges when the phone is back online.
- Home → "Problems between phones" (owner): an IMEI sold on two phones, a bill voided on one phone and returned on another, stock taken out twice, a bill number used twice; each with its bills to open.
- Account → Live sync: "All changes are in the cloud" or how many are waiting.
- Sell suggests a free bill letter and refuses one another phone already uses.
### Changed
- Stock costs are recomputed from the recorded facts after every change (one FIFO rule for this phone and merged phones).
- Staff start from the shop's copy and then sync; their separate cloud copies are gone. Logging out waits until every change is in the cloud.
- Database version 2 (existing shops upgrade automatically).
### Fixed (found while testing)
- After logging out, the phone could sign itself back in on restart (Firebase's sign-out was lost to the restart).
- A bill voided on one phone could take stock again when a return of it was voided later on another phone.

## [0.11.0] - 2026-10-15 (M7 phase 2: staff)
### Added
- More → Staff (owner only): add a salesman by phone or email, see who is waiting to join, cancel an invite, remove a staff member.
- An invited person who creates an account is asked "Join <shop> as staff?" and starts with the shop's data; they can create their own shop instead.
- A removed staff member's phone is cleared on its next check and says "You were removed from <shop>".
- Staff keep their own cloud copy; Staff and Backup & restore are hidden from staff.
### Fixed (found while testing)
- Adding staff failed with "permission denied": the app checked for an existing invite, which the rules (correctly) don't allow.

## [0.10.0] - 2026-10-15 (M7 phase 1: accounts + cloud copy)
### Added
- Welcome screen; create an account with phone + password, email + password, or Google; log in; forgot password (email accounts and phone accounts that added an email).
- Sign-up asks about the owner and the shop; a phone that already has a shop (the client's) fills it in and uploads it to the account.
- Cloud copy of the shop: saved at sign-up, after changes when leaving the app, and before log out; restored when logging in on a new or reset phone.
- More → Account: who is signed in, the shop, when it was last saved to the cloud, Log out (saves first, then removes the shop from the phone).
- Account checks: works offline; re-checks when online; blocked accounts are removed from the phone; after 14 days without a check the app asks to connect.
- Animations: screens fade and settle, tiles and rows react to touch, Home amounts count up, list rows glide, "Sale saved" pops in.
- `firestore.rules` (security rules) and `tools/reset-password` for the app owner.
### Fixed (found while testing)
- A momentary error right after restoring could be mistaken for a blocked account and wipe the phone.
- Firestore's disk cache could keep a copy of the shop on the phone after log out (now memory-only).
- "Your mobile number" was pre-filled from the phone tab during an email sign-up.

## [0.9.0] - 2026-10-15 (UI polish)
### Added
- A short confirmation at the top of the screen after every save: item, stock, payment, expense, customer, corrections, voids, app lock.
- "Sale saved" banner with the amount (and any amount on the customer's account) right after charging.
- Sell shows the next bill number ("Bill B-0003") instead of "Receipt prefix B".
- Search boxes have a clear (x) button.
### Changed
- Home money card is saffron with white numbers.
- Item, purchase and payment forms are grouped into titled cards; one consistent white search box everywhere.
- Onboarding: welcome header with the app mark, shop type as picture tiles, optional details in their own card.

## [0.8.0] - 2026-10-15 (UI refresh: icons, less text)
### Changed
- Home has 8 quick-action tiles (New sale, Add stock, Payment, Expense, Add item, Bills, Reports, Backup) under the money card.
- More is two icon menus (Business, Settings) with the current value shown (printer, lock, language) and no explanation lines.
- Receipt, party and item screens use picture tiles: Print / Share / A4 / Return / Void; Receive / Remind / Call / Edit; Add stock / Correct / Edit.
- Every list entry is its own card with an icon or the person's initials; stock history shows green plus / red minus.
- Cash / Bank / Wallet are picture tiles on every payment screen; every button carries an icon.
- Empty screens show a big picture; the empty cart has a big Scan button.
- 30 long hint and error sentences cut to one short line (English and Urdu).
### Added
- Call a customer or supplier from their page (opens the dialer).

## [0.7.0] - 2026-10-15 (M6 Hardening, part 1: everything that doesn't need the owner)
### Added
- App lock: 4-6 digit PIN with a number pad, unlock by fingerprint or the phone's own screen lock, "Forgot PIN?" through the phone lock, waits after 5 wrong tries, lock again after 0/1/5/15 minutes in the background (default 5, so sharing a receipt on WhatsApp doesn't lock).
- Showing hidden cost and profit again needs the PIN when the lock is on.
- Language: English or Urdu (اردو) from More → Language, or the phone's language; Android 13+ per-app language too. Full Urdu translation, right-to-left layout.
- App icon (shopfront on saffron, with a themed-icon layer).
- Crashlytics wiring that switches on only when a Firebase `google-services.json` is added; release signing from `keystore.properties`.
- Privacy policy draft, Data safety answers, store listing draft (docs).
- 3 PIN tests: 106 tests.
### Fixed
- Amounts in Urdu used the Nastaliq font's Latin digits; they now use IBM Plex like English.
- Release bundles would have left phones set to English without the Urdu translation (language splits now off).

## [0.6.0] - 2026-10-15 (M5 Insight)
### Added
- Home (now the first tab): money in − out today or this month with the cash/bank/wallet split, Sales · Profit · Bills, a "Needs attention" list (lines waiting for cost, items below zero, items running low, each one tap from the fix), recent sales, and a backup reminder when no backup file was saved in the last 7 days.
- First-run checklist on Home: add an item, make the first sale, set up a printer.
- Reports (More → Reports): Today / Yesterday / This month / Last month or any date range; sales after returns, cost, gross and net profit, expenses, money in − out per mode, stock value, items sold, expenses by category, customers who owe you.
- Export the report as a CSV file (opens in Excel / Google Sheets). Not offered while cost is hidden.
- Hide-cost eye on Home too.
- 5 metric tests (two-day sale/void/return scenario, cost pending, deleted expenses, ranges, CSV): 103 tests.
### Changed
- Bottom bar now has all five tabs: Home · Items · Sell · Parties · More.
- IMEI and decimal switches on the item form can be toggled by tapping their whole row.

## [0.5.0] - 2026-10-15 (M4 Money flows)
### Added
- Parties tab: customers and suppliers with what each owes (or is owed), "To collect" / "To pay" totals, search, add with opening balance.
- Party page: balance, full history (bills, returns, purchases, payments), receive/pay a payment, cancel a payment entered by mistake, edit details, "Send reminder" share text for WhatsApp.
- Void a bill (with a reason) from its receipt: stock, IMEIs and payments go back; the receipt is stamped VOID.
- Return items from a receipt: part or all of any line, IMEI units back in stock. The refund first comes off what the customer owes; only the rest is paid out in cash, and the app says exactly how much to give back.
- Cancel a wrong return.
- Expenses (More → Expenses) with quick categories; delete a mistake.
- 11 new ledger tests plus a sixth invariant (returned quantity vs. what each sale line still holds); the randomized test now also voids, returns, cancels returns and records payments: 98 tests.
### Fixed
- A voided bill's receipt showed the whole total as "Balance due".

## [0.4.0] - 2026-10-15 (M3 Hardware)
### Added
- Bluetooth receipt printing (58/80 mm ESC/POS printers): printer picker, test print, print button on receipts, auto-print after a sale.
- Camera scanning of barcodes and IMEIs in Sell, Items, Add stock (scan a whole box of phones at once) and Add item.
### Changed (design review)
- Back arrow sits at the start of every screen header.
- Bottom bar shows Items · Sell · More until Home and Parties are built.
- Item detail labels the sale price; purchase detail shows the supplier.
### Fixed
- Selected bottom-nav label was lavender (Material default) instead of ink.

## [0.3.0] - 2026-10-15 (M2 Sell + receipts + data safety)
### Added
- Sell: search by name, barcode or IMEI; IMEI picker; quantity steppers and typed decimal quantities; editable price; inline "only N in stock" note; invoice discount; tax when the shop has a rate.
- Payment step: amount received with change due, cash/bank/wallet, customer required when part is on credit.
- Receipt: preview, Share PDF (WhatsApp etc.), Print A4; Sales list with CREDIT/VOID tags.
- Receipt prefix chosen on first sale and after a restore, changeable from the Sell screen.
- Backup: automatic WAL-safe snapshot when leaving the app (Android Auto Backup uploads only that), restore on a fresh install, export/import of a `.dukaan` backup file.
- Raised Sell button in the bottom bar.
- 36 new tests (sale ledger, totals, receipt layout, backup, randomized ledger): 79 total.
### Changed
- Production database uses the bundled SQLite driver (from the eng review) — now exercised by VACUUM INTO.
- Database schema v1 is frozen: later changes need migrations.

## [0.2.0] - 2026-10-15 (M1 Catalog + stock-in)
### Added
- Onboarding: shop name, type, phone, address, currency, receipt prefix.
- Items: list with search (name, barcode, IMEI), All/Low/Negative filters, stock badges, stock value.
- Add/edit item with opening stock ("in stock now at cost"), IMEI units, sizes/colours, decimals (kg/m), barcode, low-stock alert.
- Item detail: stock per variant, IMEIs in stock, movement history, correct stock, take a unit out (damaged/returned).
- Add stock (purchase) with supplier, bill no, quantity or IMEIs per line, amount paid and mode; purchases list and detail; void purchase.
- Hide cost and profit toggle.
- More → Check stock (ledger integrity check).
- LedgerService, FIFO allocator, invariant checker; 43 unit tests.
- Bundled fonts: IBM Plex Sans/Mono, Noto Nastaliq Urdu (OFL).
### Changed (engineering review, 2026-10-15)
- Schema v1: lots record their source (purchase/opening/adjustment/return); `lot_allocation` replaces `sale_line_lot`.
- IMEI buy-back creates a new unit row; live-IMEI uniqueness via `liveSerial` unique index.
- Quantities are milli-units (`...Milli`) so items can be sold by kg/m; per-item "allow decimals".
- `businessDay` and `voidedAt` on dated documents; tax-rate snapshot on sales.
- Payments carry a kind and link reversals to the original; returns can be voided.
- Production uses bundled SQLite (`BundledSQLiteDriver`).
### Added
- DESIGN.md v1: screen layouts, components, states table, journeys, money display rules.
- `Money.format`: lakh grouping (1,26,400) and optional currency symbol.
- `Qty` value class with tests; money and quantity input accept Urdu/Arabic digits.

## [0.1.0] - 2026-10-14 (M0 Foundation)
### Added
- Android project: AGP 9.4, Kotlin 2.4.10, Compose BOM 2026.08, Room 3, minSdk 26.
- Bottom navigation shell (Home, Items, Sell, Parties, More) with placeholder screens.
- Theme tokens (saffron primary, profit/loss/warning money colors, tabular numerals).
- `Money` value class (minor units, exact arithmetic, parse/format) with tests.
- Room schema v1 (19 tables, sync-ready `SyncMeta`), exported to `app/schemas/`.
- Lint: hardcoded text and left/right RTL issues are errors.
- GitHub Actions CI: unit tests, lint, debug build.
- Project docs: PRD, architecture, design, plan, progress, decisions, testing, release, sync handoff, future features.
