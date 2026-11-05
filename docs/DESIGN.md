# Design

Status: **v1** (after `/plan-design-review`, 2026-10-15). Visual reference: the approved HTML
mockups (Sell, Home, Items, Home in Urdu), https://claude.ai/artifact/7TmC6aQvshY4bGsZGY8f3z
(private to the owner). Run `/design-review` after M2 against the running build.
Vyapar is a reference for speed and friendliness only; never copy its brand, logo or layouts.

## Principles
1. **The counter is the product.** The sell flow wins every trade-off: fewest taps, biggest targets.
2. **Numbers first.** Amounts are the largest text on any screen that has them, tabular, end-aligned.
3. **Trust through visibility.** Nothing disappears: voided sales stay visible (struck through + VOID
   tag), cost-pending and negative stock surface as a fix-it list, never hidden.
4. **Bright shop, tired eyes.** High contrast, 48dp minimum touch targets, no thin grey text.
5. **Bilingual by default.** Every layout works mirrored (Urdu RTL) with taller, longer strings.
6. **Colour means something.** Saffron = action. Green/amber/red = money state. Nothing else is coloured.

## Tokens (mirror in `core/ui/theme/Theme.kt`)
| Token | Light | Dark | Use |
|---|---|---|---|
| primary (saffron) | `#E8590C` | `#FF8A4C` | Sell action, Charge button, selected nav, links like "Fix" |
| onPrimary | `#FFFFFF` | `#1A1D21` | |
| ink / onSurface | `#1A1D21` | `#ECE7E1` | Body text, selected chips (inverted) |
| ink-2 (secondary text) | `#5A5650` | `#A8A097` | Labels, meta lines (≥ 4.5:1 on surface) |
| line | `#E6E0D8` | `#2E3134` | Row dividers, field borders |
| surface / background | `#FAF8F5` | `#121416` | Warm off-white, not pure white |
| raised | `#FFFFFF` | `#1B1E21` | Fields, pay zone, nav bar, grouped lists |
| sunk | `#F1ECE5` | `#0D0F10` | Quiet banners (backup reminder) |
| profit | `#2B8A3E` | `#69DB7C` | Profit, paid, in stock |
| loss | `#C92A2A` | `#FF8787` | Void, negative stock, overdue |
| warning | `#B35C00` | `#FFC078` | Low stock, cost pending, sell-at-zero warning |
| warning/loss/profit-bg | `#FFF1DC` / `#FDECEC` / `#E8F5EA` | `#3A2A12` / `#3A1A1A` / `#15301B` | Tag and inline-warning backgrounds |
No dynamic (wallpaper) colour. Check every new text/background pair for ≥ 4.5:1.

## Type
- **IBM Plex Sans** (D-41), bundled in the APK (400/500/600/700): one look on every phone brand,
  clear numerals with tabular figures. Not Inter, not system default.
- **Noto Nastaliq Urdu** (400/600) for Urdu, line height ×1.9 (Nastaliq is tall).
- **IBM Plex Mono** 500 only for IMEI/serial numbers (easy to read aloud and compare with the box).
- Money: tabular numerals (`MoneyTextStyle`), Latin digits even in Urdu UI (D-44), wrapped in a
  bidi isolate so they stay LTR.
- Scale (sp): hero amount 34/700 · cart total 30/700 · screen title 20/700 · row title 14/600 ·
  body 14/400 · meta 12.5/400 · section label 12/600 uppercase +0.06em (Urdu: 13/600, no caps).

## Money display (D-42, D-43)
- PKR (and INR/BDT/NPR) use **lakh grouping**: `1,26,400`. Other currencies: `126,400`.
- Decimals only when non-zero: `1,250` vs `1,250.50`.
- **Symbol only on totals**: cart lines and list rows show `42,000`; totals, hero numbers, pay
  sheet and receipts show `Rs 42,000`.
- Negative: leading minus `−2` / `−775`, plus colour, never colour alone.
- Quantity: `Qty.format()` + unit for decimal items (`38.5 m`, `1.5 kg`); SERIAL items show
  "IMEI tracked" instead of a unit.

## Spacing and shape
4dp grid; screen padding 16dp; list rows ≥ 56dp; fields and buttons 48dp, primary CTA 56dp.
Radius: 12dp fields/groups, 14dp CTA, 999 chips. One shadow only: the raised Sell nav action.
**Icon-first, cards on a tinted page (D-67, 2026-10-15 owner feedback: "too much text").** Every
action and list entry carries a picture; labels are one or two words; no explanatory sentences
under menu items. Content sits in white cards (`raised`, 1dp `line`, 14-16dp radius) on the
`#F4F0EA` page. Lists are one card per entry with an icon badge or initials on the start side.
Icons: Material Icons (Apache 2.0) as vector drawables in `res/drawable`, direction-sensitive
ones `autoMirrored`. Badge tones: BRAND saffron (selling), INFO blue (stock in, reports, bank),
PROFIT green (money in, backup), LOSS red (money out, expenses, void), WARNING amber, NEUTRAL.

## Components (build once in `core/ui`, reuse everywhere)
| Component | What it is |
|---|---|
| `MoneyText(amount, showSymbol, style)` | Formats per currency rules above, tabular, LTR isolate, colour by sign if asked |
| `QtyText(qty, unit, tracking)` | `2`, `1.5 kg`, `IMEI tracked` |
| `StockBadge(onHand, lowStock)` | End-aligned number + caption; amber when ≤ low, red + "fix stock" when < 0 |
| `LineRow` | Two-column row: title/meta start, amount end; wraps long names to 2 lines, never truncates amounts |
| `AttentionList` | Card of fix-it rows: warning/receipt icon badge + short sentence + saffron action word |
| `IconBadge(icon, tone)` | Rounded-square tinted icon, the anchor of every row and tile (core/ui/Kit.kt) |
| `ActionTile` / `ActionBar` | Icon badge + 1-2 words; rows of 3-5 in a card (Home quick actions, receipt, party, item) |
| `MenuRow` | Icon badge + title + current value + chevron (More, first-run checklist) |
| `CardRow` | One list entry as its own white card |
| `Avatar(name)` | Initials in a tinted circle for parties and items (letters only) |
| `ModePicker` | Cash / Bank / Wallet as three picture tiles, chosen one outlined saffron |
| `ButtonLabel(icon, text)` | Every button shows an icon before its words |
| `SearchField` | The one search box (Items, Sell, Parties): white, rounded, clear button, optional scan |
| `FormCard(title)` | Form section: small title + fields in a white card (item, purchase, payment, onboarding) |
| `ChoiceTile` | Selectable picture tile (payment mode, shop type) |
| `LocalNotify` | Confirmation pill ("Item saved") at the top of the screen after every save/void (D-68) |
| Brand money card | Home hero on a saffron gradient, white numbers; the only saturated block (D-68) |
| `PaySheet` | Bottom panel: subtotal, discount, TOTAL, Charge button repeating the amount |
| `ScanButton` | 48dp square next to every search field that accepts barcodes/IMEIs |
| `Tag` | Small caps label on a tinted background: VOID, CREDIT, PENDING |
| `EmptyState(title, body, action, icon)` | Big icon badge + one short line + one primary button |
| `HideCostToggle` | Eye icon in Home/Items app bar; hides cost/profit until PIN (D-45) |

## Navigation
Bottom bar: **Home · Items · [SELL] · Parties · More**. Sell is the centre, raised saffron
action. After a saved sale, Back or "New sale" returns to an empty cart.
```
 Home ─────────────── Items ──────── [SELL] ─────── Parties ─────── More
  │                    │               │               │               ├─ Purchases (stock in)
  ├─ Sale detail       ├─ Item detail  ├─ Pay sheet    ├─ Party detail ├─ Expenses
  │   └─ Void/Return   │   ├─ Edit     └─ Receipt      │   ├─ Statement├─ Reports
  └─ Fix-it targets    │   └─ Adjust                   │   └─ Payment  └─ Settings
                       └─ Add item
```

## Screens

### Onboarding (M1), one scrolling screen
```
 Welcome to Dukaan                    ← title, no illustration
 Shop name*            [ Ali Mobiles      ]   ← only required field, autofocus
 What do you sell?     (Mobiles) (Grocery) (Clothes) (Pharmacy) (Other)
 Phone / Address       [            ] [            ]
 Currency              [ PKR  Rs  ▾ ]        Language  (English) (اردو)
 Receipt prefix        [ A ]   e.g. A-0001
 Logo                  [ + Add logo ]  (optional)
 [            Start selling            ]  ← 56dp saffron, enabled once name present
```
Shop type preselects units and whether new items default to IMEI tracking (Mobiles → yes).

### Home (M5): see mockup 2
Order: app bar (shop name, date, Today/Month) → **hero = Collection** with cash/bank/wallet split
(D-29, D-40) → stat strip Sales · Profit · Bills → Needs attention (cost pending, negative stock,
low stock; hidden when empty) → Recent sales (VOID/CREDIT tags) → backup reminder (only when
last export > 7 days).

### Items (M1): see mockup 3
App bar (count; stock value only when cost is unhidden) → search + scan → chips All / Low /
Negative with counts → rows: name (2 lines max), price + variant count, `StockBadge` → "Add item"
extended button (ink, bottom end, above nav).

### Add / edit item (M1)
```
 Name*            [ Charger 25W                    ]
 Sale price*      [ 1,200 ]  per [ pcs ▾ ]
 In stock now     [ 10 ]  at cost  [ 800 ]  each     ← creates an OPENING lot (D-34); can be 0
 ( ) Track each unit by IMEI / serial                 ← switches stock entry to "scan IMEIs"
 Category         [ Chargers ▾ ]
 ▸ More options: barcode, SKU, low-stock alert at, sell in decimals (kg/m), sizes/colours
 [ Save item ]
```
SERIAL on: "In stock now" becomes a list of IMEIs (scan or type), each with its cost.
Variants: "Add sizes/colours" turns the price/stock block into one row per variant.

### Item detail (M1)
Header: name, price, StockBadge. Tabs: **Stock** (variants or IMEIs with status and cost) ·
**History** (movement log: date, type, ± qty, reference) · Actions: Edit, Adjust stock, Sell.

### Purchase / stock in (M1)
Supplier (optional, searchable) → lines (search/scan item; qty; unit cost prefilled with last cost;
SERIAL: "Scan IMEIs" keeps the camera open for bulk scanning, each beep adds a row) → total →
paid now + mode → Save. Duplicate live IMEI: row turns red, "Already in stock", Save disabled.

### Sell / cart (M2): see mockup 1
Search field focused on open, keyboard hidden until tapped (cart stays visible). Tapping an item
adds 1; SERIAL items open an IMEI picker (in-stock list + scan). Line: name, IMEI (mono), qty
stepper (decimal items: tap qty to type), amount. Zero-stock QUANTITY line shows an inline amber
note, never a dialog. Pay zone fixed at the bottom.

### Pay sheet (M2)
TOTAL (34sp) · Paid [prefilled = total] · Mode CASH/BANK/WALLET (segmented) · Customer
(optional; required when paid < total: "Rs 4,000 will be added to Ahmed's balance") · Save.
Save disables on tap (no double sale), shows a spinner in the button.

### Receipt (M2)
Preview at actual paper width → if a printer is paired, **auto-print starts** (D-46) with a
"Printing…" chip → actions: Share PDF, Print again, A4, **New sale** (primary).

### Parties, Returns/Void, Expenses (M4) · Reports (M5) · Settings (M2-M6)
Parties: Customers/Suppliers tabs, balance badge (owes you = amber, you owe = ink).
Sale detail → "Return items" / "Void sale" with a required reason; voided sale stays in lists.
Reports: date range at top, one table per report, CSV export. Settings: grouped list.

## Journeys (what the owner feels, and what supports it)
| Moment | Owner does | Feels | Design support |
|---|---|---|---|
| First 5 minutes | Installs, names shop, adds 3 items with stock | "Is this going to be work?" | One-screen onboarding, only name required; Add Item asks "in stock now" inline, no separate purchase step |
| First sale | Scans phone box, charges | Nervous, customer waiting | Scan → IMEI picked → total huge → Charge repeats the amount → auto-print |
| Busy hour | 10 sales in a row | Rushed | After save, New sale is one tap and lands on an empty cart; zero stock never blocks |
| Mistake | Wrong item sold | Worried about the numbers | Void with reason, visible VOID tag, collection auto-corrects |
| End of day | Counts the drawer | Wants it to match | Home hero = collection, split by cash/bank/wallet |
| Week 2+ | Checks profit, low stock | In control | Needs-attention list, Month toggle, Reports |

## Interaction states (every screen defines all of these)
| Screen | Loading | Empty | Error | Success | Partial |
|---|---|---|---|---|---|
| Onboarding | — | form | name missing → "Enter your shop name" | → Items (empty) | — |
| Items list | 3 skeleton rows | "Add your first item. You can add stock you already have." + Add item | DB error card + Retry | list | filter with 0 matches: "No low-stock items" |
| Item detail | skeleton | IMEI list empty: "No units in stock" + Add stock | not found → back + snackbar | — | — |
| Add item | Save spinner | — | field errors inline under the field (name, price, Urdu digits OK) | snackbar "Saved" + back | — |
| Purchase | Save spinner | "Add the first item you received" | duplicate IMEI row red; save error snackbar, form kept | → item list, snackbar | camera denied → manual IMEI entry |
| Sell cart | — | "Scan or search an item" + scan button | item search no match: "No item named 'xyz'. Add it?" | — | zero-stock line warning |
| Pay sheet | Save button spinner, inputs locked | — | save failed: snackbar, cart kept | → Receipt | paid < total without customer: inline "Choose a customer for the balance" |
| Receipt | "Printing…" chip | — | printer off/unpaired/denied: "Printer not connected" + Retry + Share PDF | toast "Printed" | — |
| Home | skeleton strip | first-run checklist: Add item · Make first sale · Set up printer | Retry card | figures | cost-pending row in Needs attention |
| Parties | skeleton | "No customers yet. They're added when you sell on credit." | Retry | list | — |
| Reports | skeleton table | "No sales in this period" | Retry | table + Export CSV | cost-pending note above profit |
| Import backup | progress | — | specific reason (wrong file / newer version / damaged) | "Restored 1,284 sales" + prefix prompt | — |

## RTL and Urdu
`start/end` only (lint enforces). Directional icons auto-mirror. Amounts, invoice numbers, IMEIs
stay LTR (bidi isolate). Nastaliq rows are ~30% taller: lists show fewer rows, nothing may assume
a fixed row height. Test every screen in Urdu at 1.3× font. Translate labels, not data.

## Small screens and large text
Design width **360dp** (common budget phones); must also work at 320dp: pay zone and nav stay
fixed, content scrolls. At 1.3× font: hero amount may wrap to two lines (allowed), stat strip
becomes 2 + 1, chips scroll horizontally. Tablets: content max width 600dp, centred.

## Signed out (D-84)
Launch: saffron window with the white store mark (Android 12+ system splash and the window
background on 8-11), replaced by the page colour once Compose draws. Landing: logo tile + name +
tagline; carousel of 4 features (white disc illustration with a dark badge, title, one line),
auto-advancing, page dots stretch for the current page; white sheet with Get started / I already
have an account (and the "shop on this phone" note). Log in / sign up / shop details / join: saffron
header (back, optional "Step n of 2" pill, headline, one line) with the form on a white sheet whose
rounded top overlaps the header.

## Home (refresh D-83)
Greeting header (shop initials badge, "Good evening · Thu, 15 Oct", shop name, hide-cost eye) →
money card (saffron gradient with soft circles; money in hand counts up; sliding Today / Month pill;
payment pills; Sales · Profit + margin · Bills with icons) → Quick actions (8 tiles) → Last 7 days
(bars, today in brand colour, week total; tap → Reports) → Needs attention → Recent sales → backup
reminder. First open: sections rise and fade in 70 ms apart; bars grow; bills slide in.

## Receipt layout (D-21, templates D-80)
Two kinds of output, each in a template the owner picks in More → Invoice design (with brand
colour, logo, tax number, terms and thank-you line; saved on the shop row, so all phones match):

| Output | Used for | Templates | Drawn by |
|---|---|---|---|
| A4 invoice | Share (PDF, vector, multi-page), A4 print | **Modern** (colour band + logo, tinted table head, total in a colour bar) · **Classic** (centred letterhead, bordered boxes, full grid) · **Minimal** (white, hairlines, total in brand colour) | `InvoicePage` on any Canvas, 595×842 pt |
| Slip | Bluetooth thermal print, slip share | **Classic** (below) · **Compact** (one line per item, small text) · **Bold** (black name band, column heads, total band) | `ReceiptLayout` rows → `ReceiptRenderer` bitmap |

The receipt screen previews either (Invoice / Slip chips); Share sends what is shown. A4 invoice
parts: header, Bill to / Payment with a PAID / DUE / VOID pill, table (# · Item + IMEI · Qty · Rate ·
Amount), totals, terms, signature line, thank-you; "VOID" watermark on voided bills; continuation
pages repeat the table head and say "Page n of m".

Classic slip:
```
        [logo]
     ALI MOBILES
  Shop 12, Hall Road, Lahore
     0300-1234567
--------------------------------
Invoice: A-0042    14-10-2026 18:30
Customer: Ahmed Raza
--------------------------------
Samsung A15 128GB Black
  1 x 42,000              42,000
  IMEI 356789012345678
Screen guard (loose)
  1.5 m x 250                375
--------------------------------
Subtotal                  42,375
Discount                    -375
TOTAL               Rs 42,000
Paid (Cash)               38,000
Balance due                4,000
--------------------------------
     Thank you! / شکریہ
```
Widths: 58mm = 384px, 80mm = 576px, A4 = 595×842pt. Labels English; item/customer names as typed.
Lakh grouping on receipts too. The logo prints in greys on slips (thermal is black and white).

## Accessibility
Touch targets ≥ 48dp. Every icon-only button has a contentDescription string resource.
TalkBack reads amounts as "rupees 42,000" (`MoneyText` sets semantics), quantities with unit,
and stock state ("minus 2, fix stock"). Nothing relies on colour alone (minus sign, tags, captions).
Focus order follows visual order in both directions.

## Cost privacy (D-45)
Until staff accounts exist, an eye toggle in Home/Items hides cost, profit and stock value
(shows "••••"). Re-showing requires the app PIN when the app lock is on (M6); without a PIN it is a plain toggle.

## Not in v1
Illustrations/onboarding carousel · custom receipt templates · themes other than light/dark ·
tablet two-pane layouts · animations beyond Material defaults.
