# Card payments at the POS

How a card payment travels, and what is real today.

## The flow

1. The desk sends the amount to a terminal (`POST /payment/payments`, method CARD). payment-service records the payment and
   asks the POS provider to push it to the terminal; the payment is **PROCESSING** with the provider's reference.
2. The guest taps or inserts the card on the terminal.
3. The provider calls our webhook with the outcome; payment-service marks the payment **APPROVED** (posted to the folio,
   receipt, events) or **DECLINED**.
4. **The desk's screen only ever asks our backend.** While a payment is at the POS it reads `GET /payment/payments/{id}`
   every 4 seconds and switches to approved or declined as soon as the backend knows. After 3 minutes without an answer it
   stops the spinner ("Still waiting for the POS") with **Check again** and **Continue to receipt**; the folio still updates
   on its own later. The browser never talks to the provider.
5. **A late or lost webhook** is caught up by the backend: `PendingPaymentCheckJob` (every 30 s) asks the provider about card
   payments still PENDING / PROCESSING that were sent 20 seconds to 30 minutes ago (`PaymentService.verify`).

## Providers (`POS_PROVIDER`)

| Value | State |
|---|---|
| `logging` (default; staging) | Development stand-in: logs the request (`[POS:LOG-…] … -> terminal …`) and never answers on its own, so a payment stays PROCESSING. A test can play the provider by posting `POST /payment/webhooks/pos/logging` with `{"paymentRequestId": "<id>", "status": "APPROVED"}` (or DECLINED). That webhook checks no signature, so `logging` must never be production's provider. The pending check has nothing to ask and does nothing. |
| `moniepoint` | **Placeholder** (`MoniepointPosProvider`): card payments fail with "Moniepoint POS is not connected yet. Take cash or a bank transfer for now." The terminal stays online. |
| `opay` | **Placeholder** (`OpayPosProvider`): the same, for OPay. |

To build a real provider, replace the placeholder's three methods (each class says what goes where): `requestPayment` (push
to the terminal, return the provider's reference), `parseWebhook` (check the signature, map the status) and `query` (look a
transaction up, used by the pending check and Verify). Keys live only in the environment.
