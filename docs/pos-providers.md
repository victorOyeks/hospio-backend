# POS providers: OPay and Moniepoint push-to-terminal

The protocols below are ported from StationMagBackend (`service/PaymentService.java`, `service/opay/OpayCryptoService.java`,
`service/TransactionService.java`). That project works against the live terminals. In Hospio, **each hotel has its own keys**
(StationMag has one set per server), stored encrypted in payment-service and shown masked.

## OPay

- **Base URL:** `https://payapi.opayweb.com/openApi` (live; no sandbox is known). Every call is a `POST`.
- **Headers:**
  - `Content-Type: application/json`
  - `version: V1.0.1`
  - `bodyFormat: JSON`
  - `clientAuthKey: <hotel's client auth key>`
  - `timestamp: <epoch millis>`
- **Body:** `{"paramContent": ..., "sign": ...}`, built in four steps:
  1. Serialise the request to JSON with keys sorted alphabetically at every level, leaving out null fields.
  2. Encrypt it with **OPay's public key** (X.509) using `RSA/ECB/PKCS1Padding`, in plaintext chunks of the key's byte length − 11 (117 bytes for 1024-bit keys).
  3. Join the ciphertext blocks and base64 the result. That is `paramContent`.
  4. `sign` = base64 of `SHA256withRSA(paramContent + timestamp)`, signed with the **merchant's private key**, which must be PKCS#8.
- **Response:** `{code, message, data, sign}`.
  - `code == "00000"` means OK.
  - `data` is base64 ciphertext. Decrypt it in chunks of the key's byte length (128 bytes) with the merchant private key, using the same padding.

| Call | Path | Request | Response `data` |
|---|---|---|---|
| Push to terminal | `/order/checkout/createOrder` | `headMerchantId`, `merchantId`, `outOrderNo` (our ref), `amount` (**naira as a string**, e.g. `"5000"`), `currency` `NGN`, `orderExpireTime` `300`, `isSplit` `N`, `sceneEnum` `CASH_API`, `subSceneEnum` `ENERGY`, `sn` (terminal serial), `productInfo`, `remark` | `orderNo` (keep as the provider ref) |
| Query | `/order/checkout/qryOrderDetail` | `headMerchantId`, `merchantId`, `orderNo` (or `outOrderNo`) | `status`, `amount` (naira), `orderNo`, `outOrderNo`, `sn`, `payType`, `senderName`, `senderAccount` (masked), … |
| Balance | `/merchant/queryMerchantBalance` | `opayMerchantId` | `usableAmount` / `amount` (kobo) |
| Payout | `/merchant/createMerchantPayOut` | `opayMerchantId`, `requestOrderNumber`, `payOutType`, `amount` (kobo), … | pays **out of** the merchant's own OPay account to a recipient |

- **Status values:**
  - `SUCCESS`, `SUCCESSFUL`, `COMPLETED` mean approved.
  - `FAIL`, `FAILED` mean declined.
  - Anything else is pending.
  - StationMag labels these values "best guess", so confirm them on the first real payment.
- **Webhook:**
  - Arrives as a `POST` whose body is an envelope: `clientAuthKey`, `version`, `bodyFormat`, `timestamp`, `paramContent`, `sign`.
  - Verify with `SHA256withRSA(paramContent + timestamp, sign)` against **OPay's public key**.
  - Decrypt `paramContent` with the merchant private key. The fields are `outOrderNo`, `orderNo`, `status`, `amount` (naira), `sn`, `senderName`, …
  - Always answer `200` with `{"code":"00000","message":"SUCCESSFUL"}`.
- **Per-hotel keys:**
  - `clientAuthKey`
  - OPay public key
  - merchant private key (PKCS#8)
  - `headMerchantId`, `merchantId`

## Moniepoint

- **Base URL:** set per environment (`MONIEPOINT_BASE_URL`).
- **Auth:** `Authorization: Bearer <hotel's API key>`.

| Call | Method and path | Request | Response |
|---|---|---|---|
| Push to terminal | `POST /v1/transactions` | `terminalSerial`, `amount` (**kobo, integer**), `merchantReference` (our ref), `transactionType` `PURCHASE`, `paymentMethod` `ANY` (or `CARD_PURCHASE`, `POS_TRANSFER`) | any 2xx means accepted (the body is ignored) |
| Status | `GET /v1/transactions/merchants/{merchantReference}` | – | `processingStatus` (`PROCESSED`, `CANCELLED`, `PENDING`, `EXPIRED`), `responseCode`, `actualAmount` (kobo), `transactionReference`, … A 404 means not seen yet, so treat it as pending |

- **Status mapping:**
  - `PROCESSED` with `responseCode` `00` is approved.
  - `PROCESSED` with any other code is declined.
  - `CANCELLED` and `EXPIRED` are declined.
  - Requests expire after about 5 minutes.
- **Webhook:**
  - Headers `moniepoint-webhook-id`, `moniepoint-webhook-timestamp`, `moniepoint-webhook-signature`.
  - The signature is base64 `HmacSHA256(webhookSecret, id + "__" + timestamp + "__" + rawBody)`.
  - Payload: `data.merchantReference`, `data.transactionStatus` (`APPROVED` or `SUCCESSFUL`), `data.responseCode` (`00`), `data.amount` (kobo), `data.terminalSerial`, `data.transactionReference`.
- **Per-hotel keys:**
  - API key
  - webhook secret
  - (base URL is server-wide)

## Debit for the daily fee

- **Neither provider has given us a merchant-authorised debit API yet.**
- OPay's payout API moves money **out of the merchant's own account** using the merchant's keys. With the hotel's consent, that could serve as the debit, sending the fee to Hospio's account, but it isn't confirmed for this use.
- Each provider's debit is configured on the server: `FEE_DEBIT_OPAY_URL`, `FEE_DEBIT_MONIEPOINT_URL`, and the account Hospio is paid into.
- An admin can switch debit on for a hotel only when its provider is configured. Until then the hotel runs in shadow mode: statements every day, no debit.
