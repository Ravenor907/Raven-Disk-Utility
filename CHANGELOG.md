# Changelog

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
