# Alternative: drive bt_drive from a generic Bluetooth terminal app

Don't want to install the NXT Drive app? Any Bluetooth Classic serial
terminal with hex macros can drive the same brick program.

## 1. Pair once

On the brick: **Bluetooth menu → turn Bluetooth ON** (it's off by default)
and **Visibility → Visible**.
On the phone: **Settings → Connections → Bluetooth → Pair new device**,
pick your brick's name, passkey **1234** (accept on the brick too if asked).

## 2. Run the brick program

Upload `bt_drive.rxe` (USB, as before) and **start it on the brick**.
It must be running *before* you send commands — the firmware drops
mailbox messages when no program is listening.

## 3. Phone app: Serial Bluetooth Terminal (Kai Morich, Play Store)

Free, current, supports Bluetooth Classic SPP and hex macro buttons.

- Open the app → devices → pick the paired NXT → connect.
- Create 5 macro buttons (M1… keys), each in **HEX** mode,
  **no newline**, with these values — each is a complete LEGO
  MessageWrite telegram ("send this letter to mailbox 1"):

| Button | Hex macro | Meaning |
|---|---|---|
| ▲ Forward | `0600800900024600` | "F" |
| ▼ Backward | `0600800900024200` | "B" |
| ◀ Left | `0600800900024C00` | "L" |
| ▶ Right | `0600800900025200` | "R" |
| ■ Stop | `0600800900025300` | "S" |

Telegram anatomy (forward): `06 00` length · `80` direct command,
no reply · `09` MessageWrite · `00` mailbox 1 · `02` message size ·
`46` = "F" · `00` terminator.

## Driving notes

- Commands latch: one tap drives until you tap Stop or another direction.
- If the phone disconnects or the app closes, the brick stops itself
  (the program watches the Bluetooth link).
- Robot turns the wrong way on L/R? Your left/right motors are swapped
  relative to the program's assumption (A = left). Swap the A/B cables at
  the brick, or swap the port definitions in `bt_drive.nxc` and rebuild.
