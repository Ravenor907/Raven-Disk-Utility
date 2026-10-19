<p align="center">
  <a href="https://github.com/Ravenor907/Raven-Disk-Utility/releases"><img src="https://img.shields.io/github/v/release/Ravenor907/Raven-Disk-Utility?include_prereleases&label=Android%20APK&style=for-the-badge&color=8B7CFF&labelColor=14181D" alt="Android APK"></a>
  <a href="https://github.com/Ravenor907/Raven-Disk-Utility/actions/workflows/android.yml"><img src="https://img.shields.io/github/actions/workflow/status/Ravenor907/Raven-Disk-Utility/android.yml?branch=main&label=tests&style=for-the-badge&labelColor=14181D" alt="Tests"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-6FD3FF?style=for-the-badge&labelColor=14181D" alt="GPL-3.0"></a>
</p>

# Raven Disk Utility

A partition manager for your phone. Plug in a USB drive, SSD, hard drive or card reader and do
what you'd normally need a PC with GParted or GNOME Disks for.

External drives only: it never touches your phone's own storage and never needs root. No pretend
buttons either: if something can't be done, the app says why.

## What it does

- **See everything.** Every drive's partition table, partitions, filesystems, labels, sizes,
  alignment, free space and health, on a map you can tap.
- **Partition.** New GPT or MBR tables, create, delete, resize (empty partitions), rename, change
  types, GPT flags, the MBR active flag.
- **Format.** exFAT, FAT32, ext4 and Linux swap, written by the app itself and checked against
  `fsck`, `e2fsck` and the Linux kernel on every build.
- **Relabel** FAT, exFAT, ext2/3/4 and swap in place.
- **Erase** by overwriting with zeros, or discard (TRIM) on drives that support it, with honest
  notes on what each does and doesn't guarantee.
- **Health.** SMART for drives in USB-SATA enclosures: temperature, hours, reallocated and pending
  sectors, the drive's own verdict.
- **Review first.** Changes queue up. You see before and after, what data goes away, and confirm.
  Then the app checks it's still the same drive, writes, and reads everything back.

It also reads (but doesn't create yet) NTFS, Btrfs, XFS, F2FS, UDF, LUKS, BitLocker and a
few others.

## Get it

Grab the APK from [Releases](https://github.com/Ravenor907/Raven-Disk-Utility/releases), plug the
drive in with a USB-C adapter or hub, and say yes when Android asks if Raven can use it.

If Android already mounted the drive, eject it in Settings first (or tap **Open anyway**:
it's the same as pulling the drive out from under Android).

Want to look around first? **Capabilities → Show simulated drives** gives you three made-up drives
in memory to try everything on.

## Scope

Raven is for external drives: USB sticks, SSDs and hard drives in USB enclosures, and card readers.
No root, ever. Still to come:

- Checking and repairing filesystems.
- Resizing and moving partitions that hold data.
- Creating NTFS.
- NVMe health through USB-NVMe enclosures.

A few fast enclosures only speak UAS, which Android doesn't let apps use at all; they're listed
but can't be opened. Most enclosures fall back to the mode that works.

The long version, with the why: [docs/FEASIBILITY.md](docs/FEASIBILITY.md).

## Build

```bash
./gradlew :core:test :app:assembleDebug
```

JDK 17 and the Android SDK (API 36). The disk engine in `core/` is plain Kotlin, so its tests run
anywhere without an emulator. CI also checks it against the real Linux tools both ways (images
from `mkfs`/`sgdisk` must read like `blkid` says; images the app makes must pass
`sgdisk --verify`, `fsck` and a real mount), and screenshots every screen on Android 14 and 16.

How it fits together: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Careful

This app rewrites partition tables. It checks a lot before it does, but a plan is only as right
as the drive you pick. Back up what matters. There's no undo once writing starts.

GPL-3.0. Sibling of [Handheld Forge](https://github.com/Ravenor907/Handheld-Forge).
