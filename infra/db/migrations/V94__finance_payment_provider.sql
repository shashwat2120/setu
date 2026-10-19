-- A real payment provider (Razorpay) in front of Finance.
--
-- student_charge_id : the fee a payment settles. The amount of a charge-linked payment comes from the charge, never from the client, and a
--                     success marks the charge PAID.
-- provider_payment_id: the provider's own id of the money that moved (pay_...), kept so a refund can be sent to the provider. provider_ref
--                     (V44) already holds the provider's order id (order_...). Neither column ever holds a card number or CVV.
ALTER TABLE finance.payments
    ADD COLUMN student_charge_id   UUID NULL REFERENCES finance.student_charges (id),
    ADD COLUMN provider_payment_id TEXT NULL;

-- A provider order belongs to exactly one payment, and so does a provider payment: a webhook finds our row by these.
CREATE UNIQUE INDEX uq_payments_provider_ref ON finance.payments (provider_ref) WHERE provider_ref IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_provider_payment ON finance.payments (provider_payment_id) WHERE provider_payment_id IS NOT NULL;

-- A charge has at most one payment in flight or paid: a second click on "Pay" reuses the open payment instead of creating a second order,
-- and two payments can never both settle the same fee. A FAILED or REFUNDED payment no longer counts, so a retry is allowed.
CREATE UNIQUE INDEX uq_payments_open_per_charge ON finance.payments (student_charge_id)
    WHERE student_charge_id IS NOT NULL AND status IN ('INITIATED', 'SUCCEEDED');
