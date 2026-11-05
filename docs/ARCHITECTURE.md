# Architecture

## Overview
```
 UI (Compose) ──▶ ViewModel (StateFlow<UiState>) ──▶ Repository ──▶ Room DAO ──▶ SQLite
     │                                                  │
     ├── Scanner (CameraX + ML Kit, on-device)          ├── LedgerService: every stock/FIFO/serial change,
     │                                                  │   one @Transaction, all or nothing
     ├── InvoicePage (A4 template → Canvas in points, D-80)
     │       └──▶ PdfDocument pages ──▶ Share intent (WhatsApp) / PrintManager (A4)
     ├── ReceiptRenderer (slip template → Bitmap)
     │       └──▶ ESC/POS raster ──▶ Bluetooth thermal printer; slip PDF
     └── AppLock (BiometricPrompt + PIN hash in DataStore)
 DataStore: settings, deviceId, invoicePrefix      Crashlytics: crashes only, no business data
 Backup: on app background → VACUUM INTO filesDir/backup/dukaan.db → Auto Backup includes only that + logo
 Export/Import: same snapshot zipped as .dukaan via the system file picker (SAF)
```

- Single `:app` module. Manual DI through `AppContainer` (D-23). Concrete classes, no single-impl interfaces.
- Packages: `core/{db,money,ledger,receipt,printer,scan,backup,lock,ui}`,
  `feature/{onboarding,home,sell,items,purchase,parties,returns,expenses,reports,settings}`.
- One screen = `XScreen` + `XViewModel` + `XUiState`. ViewModels never touch DAOs directly for
  stock-changing work; they call `LedgerService`.
- Stock state is **derived** (D-75): LedgerService writes facts (documents, movements, IMEI events)
  and `StockRebuild` → `StockReplay` recomputes lots, allocations, line cost/pending, return cost,
  IMEI status and onHandMilli for the variants touched. The same rebuild runs after a sync merge
  (docs/SYNC.md). Derived writes never change `updatedAt`.

## Data model
Source of truth: `app/src/main/java/app/dukaan/android/core/db/Entities.kt` (keep this section in sync).
Every table: `id TEXT` (UUID) PK + `SyncMeta(createdAt, updatedAt, deletedAt?, deviceId)`.
Units: `...Paisa` = money (Long minor units), `...Milli` = quantity (Long thousandths: 2.5 kg = 2500,
1 phone = 1000, D-39), `businessDay` = Int yyyyMMdd in the shop's time zone, fixed at write (D-36).

```
 shop_profile (timeZone)   category ──< product (allowDecimalQty) ──< variant (onHandMilli cache)
                                                                         │
 party(SUPPLIER) ──< purchase ──< purchase_line ──┐                      │
                                                  ├─ source of ─▶ stock_lot (FIFO) ──< lot_allocation
 opening stock / +adjustment / return ────────────┘                serial_unit (one row per ownership
                                                                     cycle; liveSerial unique) ──< serial_event
 party(CUSTOMER) ──< sale ──< sale_line ──< lot_allocation (consumer SALE_LINE)
                      │           └── serial_unit (SERIAL lines, qty 1)
                      ├──< sale_return (status) ──< sale_return_line
                      └──< payment (kind, reversesPaymentId)      stock_adjustment ──< lot_allocation (−)
 expense                                                          stock_movement (every qty change)
```

| Table | Key columns | Notes |
|---|---|---|
| shop_profile | name, shopType, currency, **timeZone**, taxRateBp?, logoPath | One row; its existence = onboarding done |
| product | name, categoryId?, trackingType, unit, **allowDecimalQty** | SERIAL ⇒ allowDecimalQty false |
| variant | productId, label, sku?, barcode?, salePricePaisa (per 1 unit), purchasePricePaisa? (usual buying price, newest purchase cost; D-85), lowStockMilli?, **onHandMilli** | ≥1 per product ("Default") |
| party | type, name, phone?, openingBalancePaisa | Positive = owes the shop |
| purchase / purchase_line | supplierId?, date, businessDay, total, status, voidedAt / variantId, qtyMilli, unitCostPaisa | |
| stock_lot | variantId, **sourceType** PURCHASE/OPENING/ADJUSTMENT/RETURN, sourceId?, qtyIn/RemainingMilli, unitCostPaisa, receivedAt | FIFO order (receivedAt, createdAt, id) (D-34) |
| lot_allocation | lotId, consumerType SALE_LINE/ADJUSTMENT, consumerId, qtyMilli, **reversedQtyMilli**, unitCostPaisa | Replaces sale_line_lot; a row can't be reversed twice |
| serial_unit | serial, **liveSerial** (= serial while IN_STOCK, else null; unique), variantId, sourceType/Id, costPaisa, status | Buy-back = new row (D-33, D-35) |
| sale | prefix + seq (unique), customerId?, date, businessDay, subtotal, discount, **taxRateBp snapshot**, tax, total, status, voidedAt | |
| sale_line | nameSnapshot, qtyMilli, unitPricePaisa, lineTotalPaisa, costPaisa, **pendingQtyMilli**, serialUnitId? | Snapshots never change |
| sale_return / _line | saleId, businessDay, refund, **status, voidedAt** / saleLineId, qtyMilli, refundPaisa, costReversedPaisa | Returns can be voided (D-38) |
| stock_adjustment | variantId, qtyDeltaMilli, unitCostPaisa? (for +), reason, businessDay | + creates a lot; − consumes FIFO |
| payment | **kind** SALE/PURCHASE/REFUND/PARTY/REVERSAL, party/sale/purchase/return ids, **reversesPaymentId** (unique), direction, mode, amount, businessDay | |
| expense | category, amountPaisa, date, businessDay | |
| stock_movement | variantId, type, qtyDeltaMilli, refId | Append-only |

**Derived (D-75):** onHandMilli, qtyRemainingMilli, status/liveSerial, costPaisa/pendingQtyMilli,
costReversedPaisa and the whole lot_allocation table are recomputed by `StockRebuild`; allocations
may be deleted and rebuilt. Device-local bookkeeping: `sync_outbox`, `sync_state` (no SyncMeta, never synced).

**Deletion rule (D-27):** soft delete (`deletedAt`) exists only for master data: category,
product, variant, party, and expense (D-59). Everything else is ledger: never deleted, never soft-deleted. Voids set
`status = VOIDED` + `voidedAt` and write reversal rows. Ledger queries never filter on `deletedAt`.

**Why no SQL foreign keys:** after multi-device sync, rows can arrive in any order. Integrity is
guaranteed by LedgerService transactions and checked by the invariants below.

**Enums** are stored as their name (TEXT). Never rename an enum constant without a migration.

**SQLite (D-32):** the app ships its own SQLite (`BundledSQLiteDriver`), the same build the JVM
tests use. Android 8-10 system SQLite is too old for `VACUUM INTO`.

## Invariants
Checked by tests after every operation and by Settings → "Check & repair stock". After a sync merge,
a clash (docs/SYNC.md, D-78) can break 2, 3 or 6 until the owner fixes it; 1, 4 and 5 always hold.
1. `variant.onHandMilli == Σ stock_movement.qtyDeltaMilli`.
2. QUANTITY items: `onHandMilli == Σ stock_lot.qtyRemainingMilli − Σ sale_line.pendingQtyMilli` (ACTIVE sales).
3. SERIAL items: `onHandMilli == 1000 × count(serial_unit WHERE status = IN_STOCK)`.
4. `sale_line.costPaisa == Σ lot_allocation (qtyMilli − reversedQtyMilli) × unitCost / 1000` (QUANTITY) or the serial cost.
5. Every lot: `qtyRemainingMilli == qtyInMilli − Σ its allocations (qtyMilli − reversedQtyMilli)` (0 if its purchase is voided).
6. QUANTITY sale line: `pendingQtyMilli + Σ live allocations == qtyMilli − Σ ACTIVE returned qty` (0 for a voided sale).

## Serial unit state machine
```
 purchase / opening ──▶ IN_STOCK ──sell──▶ SOLD ──void sale / customer return──▶ IN_STOCK
                           │                 (only if this exact row is SOLD; else refuse)
                           ├──adjust──▶ DAMAGED / RETURNED_TO_SUPPLIER        (terminal)
                           └──purchase void──▶ VOIDED                          (terminal)
 Buy-back of a SOLD/terminal IMEI = INSERT a NEW row (IN_STOCK, new cost). Old row never changes.
 liveSerial = serial only while IN_STOCK → unique index blocks a second live copy of an IMEI.
 SERIAL sale lines always have qtyMilli = 1000 and one serialUnitId.
```
Each transition writes a `serial_event` row (history shown on the item detail screen).

## Transactions (LedgerService; each is one Room transaction)
```
 SALE: validate cart ──▶ seq = MAX(invoiceSeq for prefix) + 1 ──▶ sale + lines (snapshots)
   │ empty cart → Save disabled           ├─ QUANTITY line: consume lots in FIFO order → lot_allocation
   │ serial not IN_STOCK → field error     │    rows; shortfall → pendingQtyMilli, onHand may go
   │ qty ≤ 0 / price < 0 → field error     │    negative (warning shown before save)
   │ decimal qty, whole-unit item → error  ├─ SERIAL line: unit → SOLD, liveSerial = null, cost = unit cost
   │ paid < total and no customer → error  ├─ stock_movement rows + onHandMilli cache
   │                                       └─ payment(kind SALE, IN) for paid part; rest = credit
   └──▶ Receipt screen. Any exception → rollback; nothing saved; snackbar explains.

 LOT IN (purchase line / opening stock / +adjustment / return of QUANTITY): new stock_lot with
   sourceType, then FILL PENDING: oldest ACTIVE sale lines of the variant with pendingQtyMilli > 0
   take from this lot first (lot_allocation rows), then movements + onHand.
 PURCHASE: lines → LOT IN (QUANTITY) / new serial_unit rows (SERIAL) → payment(kind PURCHASE, OUT).
 −ADJUSTMENT: QUANTITY consumes lots FIFO (lot_allocation consumer ADJUSTMENT); a shortfall is
   refused ("only N in stock"); SERIAL sets the chosen unit DAMAGED / RETURNED_TO_SUPPLIER.

 PURCHASE VOID: allowed only if no allocation exists on its lots and every serial from it is
   IN_STOCK; then PURCHASE_VOID movements, lots qtyRemaining = 0, serials → VOIDED (liveSerial null),
   REVERSAL payment, status VOIDED + voidedAt. Otherwise refuse with "use stock adjustment".

 SALE VOID: refused while the sale has an ACTIVE return (void the return first). Otherwise:
   reverse remaining allocations (reversedQtyMilli = qtyMilli, lot qtyRemaining += rest),
   pendingQty cleared, its serial rows → IN_STOCK, VOID movements, REVERSAL payment for each
   payment of the sale, status VOIDED + voidedAt.

 RETURN (partial): per returned qty: reduce pendingQtyMilli first, then reverse allocations
   newest first (reversedQtyMilli); SERIAL row → IN_STOCK. Refund by cumulative rounding (below).
   Refund reduces the customer's balance first; payment(kind REFUND, OUT, returnId) only for the excess (D-60).
   A serial whose IMEI was bought back and is in stock again cannot come back (refused; same for sale void).
 RETURN VOID: QUANTITY lines take the stock again FIFO from today's lots, shortfall → pending (D-58);
   serial → SOLD if still IN_STOCK, else refuse; REVERSAL of its refund payment, status VOIDED + voidedAt.
 PARTY PAYMENT: payment(kind PARTY), IN from a customer / OUT to a supplier; cancelled by a REVERSAL.
```

### Rounding and tax (D-37)
Order: line totals (`round(unitPrice × qtyMilli / 1000)`, half-up) → subtotal → invoice discount →
tax on (subtotal − discount) at the snapshotted `taxRateBp`, half-up → total.
Each line's **net** = its share of (subtotal − discount + tax), pro-rata by lineTotal, with the
rounding remainder on the last line, so Σ line nets == sale total exactly.
Return refunds use **cumulative rounding**: refund = `round(lineNet × returnedSoFarMilli / qtyMilli)
− refundedSoFar`, so any sequence of partial returns sums to exactly the line net.

### FIFO allocation (pure function, `core/ledger/FifoAllocator`, M2)
Input: lots ordered by (`receivedAt`, `createdAt`, `id`) with `qtyRemainingMilli`, qty wanted (milli).
Output: list of (lotId, qtyMilli, unitCost) + `pendingQtyMilli` shortfall. No I/O, unit-tested exhaustively.

### Invoice numbering (D-18)
Prefix lives in device-local DataStore (`A` by default; asked again after every restore/import).
Number = `MAX(invoiceSeq) + 1` for that prefix, computed inside the sale transaction; the unique
index on `(invoicePrefix, invoiceSeq)` is the last line of defence. Display `A-0001` (4-digit pad, grows past 9999).

## Business metrics (D-29): one definition each, used by Home and Reports
| Metric | Definition | Why |
|---|---|---|
| **Collection** | Σ payment IN − Σ payment OUT (REVERSALs included, so a void nets to zero on the day it is done), by payment **businessDay**, split by mode (cash/bank/wallet) | Must match the cash drawer: credit sales add nothing until paid; debt recoveries and refunds count on the day they happen |
| **Sales** | Σ totalPaisa of ACTIVE sales by sale businessDay − Σ refunds of ACTIVE returns by return businessDay (a voided bill leaves its day, D-61) | What was sold, paid or not |
| **Gross profit** | Sales − cost, cost = Σ (line cost + cost reversed by its ACTIVE returns) on the sale day − Σ return costReversedPaisa on the return day (D-60, D-61). `ReportDao.summary` | Lines with pendingQty > 0 are flagged "cost pending" |
| **Net profit** | Gross profit − Σ expense by expense date | |
| **Balance** | per party (`PARTY_BALANCE` in Daos.kt): opening + ACTIVE sales − ACTIVE return refunds − ACTIVE purchases + payments OUT − payments IN. Positive = owes the shop | "Who owes what"; reversals cancel by direction |
| **Stock value** | Σ lot.qtyRemaining × unitCost + Σ IN_STOCK serial cost | At cost, not sale price |
Days are the stored `businessDay` (yyyyMMdd in `shop_profile.timeZone`, fixed at write, D-36). Reports never derive days from timestamps, so a timezone/clock change can't move history, and a void shows on the day it was done (closed days never change).

## Money
`Money` (Long minor units) and `Qty` (Long milli-units) in `core/money`. Both parse strictly and
accept Urdu/Arabic digits (`normalizeNumberInput`). Tax rates are basis points `Int`.
`Money.times(Qty)` rounds half-up to the paisa. Rounding rules: § Rounding and tax.

## Backup and restore (D-11)
- On `ON_STOP` (app to background) and at most once per hour (D-28):
  1. `VACUUM INTO 'filesDir/backup/dukaan.db.tmp'` (consistent snapshot, WAL-safe)
  2. open the tmp file, `PRAGMA integrity_check` must return `ok`, close
  3. `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` `dukaan.db.tmp` → `dukaan.db` (same directory; `File.renameTo` cannot replace on every platform)
  Any failure: delete tmp, keep the previous good snapshot, log a Crashlytics non-fatal.
  A process kill at any step leaves the last good `dukaan.db` intact.
  Auto Backup rules include only `backup/dukaan.db` and the logo; the live DB and `*.tmp` are excluded.
- Export: zip of snapshot + `manifest.json` (schema version, app version, createdAt) via SAF `CreateDocument`.
- Import: validate zip → manifest schema version ≤ current → open snapshot read-only and run
  `PRAGMA integrity_check` → copy current DB aside → swap → regenerate deviceId → ask for new prefix.
- Home shows a reminder when the last manual export is older than 7 days.

## Error handling
See the table in `PLAN.md` § Error handling; named exceptions only, no catch-all. Print failures
never roll back a saved sale. Crashlytics records non-fatals for print/import/migration failures
without amounts, names or phone numbers.
