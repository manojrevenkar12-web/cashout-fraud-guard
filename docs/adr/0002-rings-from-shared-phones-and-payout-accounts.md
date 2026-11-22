# 2. Rings from shared phones and payout accounts, told apart by a selfie

## Context
Farms run many accounts on a few emulator images and cash out to a few PayPal accounts. Multi-account players reset
one phone. Families share a phone, sometimes a PayPal. All three look like "accounts sharing a device".

## Decision
Link accounts that share a hardware fingerprint or a payout account, with union-find. When an account in a ring asks to
cash out without a recent selfie, ask for one. Different faces pay; a face already seen on another account, or a face
that changed on the same account, waits for an analyst. When an analyst confirms fraud, the rest of the ring is
decided in the same review from one case file.

## Consequences
The `no_graph`, `no_step_up` and `no_ring_review` designs measure each part. A family member who skips the selfie
waits; after three tries an analyst sees it.
