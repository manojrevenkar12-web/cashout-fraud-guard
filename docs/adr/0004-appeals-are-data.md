# 4. Appeals are data, read against a closed schema

## Context
Players explain holds: "my son plays on the same phone", "I use a VPN for privacy". Some are true, some are scripts.
A model reading free text can be told to pay.

## Decision
A reader returns only a language and one of five claims. The Claude reader forces a tool call whose schema allows
only those values; anything else falls back to the rule reader. A claim moves the case up the queue only when it
explains one of the hold's reasons and no hard evidence (a reused or changed face, a confirmed ring, an emulator,
scripted play) is against it. The reply draft is in the player's language and never names a check.

## Consequences
A reading cannot pay, unblock or reveal anything. Support time per appeal falls with the draft; the
`no_reply_drafts` design measures it.
