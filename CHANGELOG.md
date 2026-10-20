# Changelog

## 0.5.0

- **Write an image.** Pick an .iso, .img or .raw (or compressed .xz, .gz, .bz2, .zip) and Raven writes it to the drive, then reads every byte back to confirm. DVD-only ISOs and unknown files get a warning first.
- **Speed and capacity test** with a live graph: write and read speed as it happens, with average, peak and low. Shows when a stick slows down after its fast cache fills, and checks markers up to the last megabyte to catch fake capacity. **Format drive** is right there afterwards.
- Faster USB: big reads and writes now keep several transfers in flight, and fall back to one at a time on the first hiccup.
- Sturdier USB recovery: a write that chokes resets the drive and retries in smaller pieces instead of hanging.
- Raven now shares one drive engine with Handheld Forge, so formatting, image writing and the speed test behave the same in both.

## 0.4.3

- No more typing ERASE. Ticking the confirmation box on the review screen is enough, even when the whole drive is wiped.

## 0.4.2

- **Unmount from the app.** When Android has a drive mounted, the drive page shows an **Unmount** button. No more trips to Settings before formatting.
- On the drive list, **Unmount and open** takes a drive from Android and opens it in one step.
- Fixed: a drive could still show as "mounted by Android" after Raven had already taken it over, which blocked formatting.
- New look from the app icon: night-navy background, charcoal panels, copper accents. The raven emblem sits in the header.
- New app icon.

## 0.4.1
- Includes the 0.4.0 changes below (0.4.0 itself never shipped because of a build error).
- Raven no longer pops up when a USB drive is plugged in. Open it yourself when you want it; drives plugged in while it's open still show up right away.

## 0.4.0
- **Format drive** sits at the top of every drive: pick exFAT, FAT32 or ext4, add a name, review, done.
- Tapping a partition shows a big **Format this partition** button first.
- New partition table, secure erase and re-reading the drive moved into a collapsed **Advanced** section.

## 0.3.4
- Fixed USB drives that stop responding after a short answer (seen on SSK USB 3.2 sticks): Raven now clears the stalled pipe the way the USB spec says instead of resetting the drive.

## 0.3.3
- Much fuller troubleshooting report when a drive can't be opened: every USB call with its result and timing, phone model and Android build, USB permission, attached devices and Android's own volumes.
- **Share** button next to Copy on the error card.

## 0.3.2
- Keeps trying all connection methods even if reopening the drive fails, and reports what happened on each.

## 0.3.1
- Tries four ways to connect to a drive (plain, select USB mode, reset, async transfers).
- Error card shows a plain-English reason, a **Details** view and a **Copy** button.

## 0.3.0
- Always switches USB 3 sticks into the standard (Bulk-Only) mode before use, retries with resets.
- External drives only: removed every root mention and the phone's internal storage from the app.
- Simple partition icon.

## 0.2.0
- **ext4 formatting**, built into the app (journaled, checked with e2fsck), for Linux drives.

## 0.1.0
- First release: drive list, partition map, GPT/MBR tables, create, delete, resize empty partitions, rename, types, flags, format exFAT/FAT32/swap, labels, erase, SMART health, review before writing, operation log.
