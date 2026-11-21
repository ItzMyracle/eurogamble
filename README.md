# EuroGamble (Fabric 1.21.11, client-side)

Gambling tracker for EuropeMC. Reads the ECONOMY payment lines in chat and keeps the books.

* Sessions (start / end / delete / rename), profit, money in/out, W-L, win rate, streaks (session + all-time)
* Bet limits (min/max) with a "Flagged" list and copy-able /pay refund commands
* Rakeback tab: per player wagered, rakeback owed (0.5 - 50 %), click a player to see all their payments
* Losing streak alert after N lost rounds in a row
* Safety prompt: yes/no window before a /pay at or above your limit leaves your client
* Chat buttons under incoming payments: [0.5x] [payback] [1.5x] [2x] [3x] [4x] [bal]
* HUD: corner panel (top right) and a bar above the hotbar, both switchable
* Pop-up notifications for payments

Open the tracker with B. Change the key in Options > Controls > Key Binds > EuroGamble.

## Commands
/eurogamble (or /eg)  min | betmin | betmax | confirm <250k, 5M, 1B, 1T>, toggle, sound, debug, reload, test [amount]

## If payments are not detected
/eurogamble debug true, get paid, look at the printed line and edit config/eurogamble.json
(prefix, incomingKeywords, outgoingKeywords, incomingPlayerPatterns, outgoingPlayerPatterns).

Settings: config/eurogamble.json, data: config/eurogamble-sessions.json
(old Pay Notifier files are taken over once).
