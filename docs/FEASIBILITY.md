# What an Android app can actually do to disks

This is the groundwork the rest of Raven Disk Utility is built on. Every operation the app offers
maps to one of the access paths below, and the app only shows an operation when its path is
available on the phone in front of it.

## 1. A normal app (no root)

A regular app runs as its own Linux user under SELinux's `untrusted_app` domain. It cannot open
`/dev/block/*`, cannot read most of `/sys/block`, cannot mount, unmount or format, and cannot run
`fdisk`, `mkfs` or `fsck` against any device. Raw access to the phone's own storage is impossible
without root. That's by design and no API changes it.

What it can do:

- **Read storage volume info** through `StorageManager` and `StorageStatsManager`: internal
  storage capacity and free space, mounted SD cards and USB drives (description, state, filesystem
  serial, removable or not), and `StatFs` sizes for each.
- **Read its own mount table** (`/proc/self/mounts`), which usually names the filesystem type
  Android mounted a card or USB drive with.
- **Talk to USB devices directly** through the USB Host API (next section). This is the one
  path that gives real, block-level access without root.

## 2. Storage Access Framework

SAF hands out document trees and files (`content://` URIs). It's a file-level API: no access to
partition tables, boot sectors, or the block device under a volume, and no way to format or label.
It's useful for reading or writing files on a mounted drive, which isn't this app's job, so the app
doesn't use it.

## 3. Raw block access without root: USB Host

`UsbManager` lets an app, after the person approves a permission prompt, claim a USB interface and
send bulk transfers to it. A USB mass storage device using **Bulk-Only Transport** (class 8,
subclass 6, protocol 0x50) accepts SCSI commands over those transfers: INQUIRY, READ CAPACITY,
READ/WRITE(10/16), SYNCHRONIZE CACHE, UNMAP, and ATA PASS-THROUGH on SATA bridges.

That's a complete block device, implemented in the app itself (`core/io/Scsi.kt`), which is what
lets Raven read and write partition tables and create filesystems on USB drives on stock Android.
Handheld Forge uses the same path to write boot drives.

Limits of this path:

- **Claiming the interface detaches Android's kernel driver.** If Android had the drive mounted,
  that's like pulling it out. The app detects Android-mounted USB volumes and asks the person to
  eject first (or to inspect anyway, knowingly).
- **UAS-only devices can't be used.** USB Attached SCSI uses streams that Android's USB Host API
  doesn't expose. Most UAS enclosures also offer Bulk-Only as a fallback setting, which the app
  uses; the few that don't are listed as unsupported.
- **Health data depends on the bridge.** USB-SATA bridges with SCSI/ATA Translation pass SMART
  through (ATA PASS-THROUGH 16). Flash drives and card readers have no SMART. USB-NVMe bridges
  (JMicron JMS583, Realtek RTL9210, ASMedia) use vendor-specific commands; those aren't
  implemented yet, so NVMe health over USB shows as unavailable rather than guessed.
- **Discard** works when the drive reports logical block provisioning (READ CAPACITY 16 LBPME plus
  the Block Limits VPD page). Many flash drives don't.
- **Secure erase.** ATA SECURITY ERASE and SCSI SANITIZE through a bridge can leave a drive locked
  or bricked when a bridge mistranslates them, so they're not offered. The app offers an honest
  zero overwrite and discard, and says what each does and doesn't guarantee.

## 4. Root: not used, by design

Raven is for external drives, and everything it does on them works through USB host access with
no root. It never asks for root and never touches the phone's own storage. Root would only add
the phone's internal partitions and SD card slot, which is exactly where a mistake can stop a
phone from booting, so it stays out of scope. Where an external-drive feature would normally
need system tools (ext4, fsck), Raven implements it itself instead.

## 5. Recovery, privileged system apps, separate Linux

- A **custom recovery** (TWRP) or **fastboot** can repartition the phone's own storage. That's
  outside an app's reach and outside this app's scope.
- A **privileged system app** (signed with the platform key) could use vold's `partition`,
  `format` and `mount` calls. Not available to an installable APK.
- **Linux-based handhelds** (SteamOS, Bazzite, CachyOS on Steam Deck, ROG Ally, Legion Go) have
  full disk tools natively; there an APK only runs inside Waydroid, which sees no real block
  devices. The right tool there is KDE Partition Manager or GNOME Disks. The UI/backend split here
  would allow a desktop front end later, but that isn't planned for 0.x.

## 6. Filesystem tools

Android doesn't ship tools an app can use for exFAT, NTFS, Btrfs or XFS, and its own `mke2fs` and
`e2fsck` can't be pointed at a USB drive an app has claimed. So Raven writes filesystems with its
own code: **FAT32, exFAT, ext4 and Linux swap** today (plus label changes for FAT, exFAT, ext2/3/4
and swap). Every build checks them against `fsck.vfat`, `fsck.exfat`, `e2fsck`, `blkid`,
`sgdisk --verify` and a real mount. Checking, repairing and resizing are being built the same way.

## 7. How USB storage shows up on Android

When nothing else claims it, the kernel's `usb-storage` (or `uas`) driver binds the device, vold
reads its partition table and mounts the first supported filesystem (FAT, exFAT, and on some
phones NTFS) under `/mnt/media_rw/<UUID>`, exposed to apps at `/storage/<UUID>`. The
`StorageVolume` UUID is the filesystem serial, which the app matches against what it reads from
the drive to tell which partition Android had mounted.

USB-to-SATA and USB-to-NVMe enclosures look exactly like flash drives at this level: a SCSI disk
behind Bulk-Only or UAS. The bridge decides what else passes through.

## 8. What works

| | Raven (any Android phone with USB host) |
|---|---|
| USB drives, SSDs and HDDs in enclosures, card readers (Bulk-Only) | Partition, format FAT32/exFAT/ext4/swap, label, erase |
| SMART over USB-SATA bridges | Yes |
| Check, repair, resize with data, NTFS create | Being built, in-app |
| NVMe health over USB bridges | Not yet |
| UAS-only enclosures | Not possible (Android limit) |
| The phone's own storage, SD card slot | Never touched, by design |
