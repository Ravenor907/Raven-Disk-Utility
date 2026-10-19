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

## 4. What needs root

With `su` (Magisk, KernelSU) a shell can open `/dev/block/*` and run the tools Android itself
ships: `mke2fs`, `e2fsck`, `resize2fs`, `make_f2fs`, `fsck.f2fs`, `newfs_msdos`, `fsck_msdos`,
and sometimes `sgdisk`. That adds:

- Partitioning and formatting **SD card slots** (which are not USB devices) and UAS-only enclosures.
- Creating, checking, repairing and resizing **ext4 and F2FS**, and checking FAT.
- Inspecting the phone's internal layout (read-only by policy: Raven will never write to the
  phone's own storage).

Root never happens implicitly: running `su` shows a grant prompt, so the app only runs it when the
person taps "Check root access". Version 0.1 detects root and which tools exist and reports them;
the root execution backend is the next milestone.

## 5. Recovery, privileged system apps, separate Linux

- A **custom recovery** (TWRP) or **fastboot** can repartition the phone's own storage. That's
  outside an app's reach and outside this app's scope.
- A **privileged system app** (signed with the platform key) could use vold's `partition`,
  `format` and `mount` calls. Not available to an installable APK.
- **Linux-based handhelds** (SteamOS, Bazzite, CachyOS on Steam Deck, ROG Ally, Legion Go) have
  full disk tools natively; there an APK only runs inside Waydroid, which sees no real block
  devices. The right tool there is KDE Partition Manager or GNOME Disks. The UI/backend split here
  would allow a desktop front end later, but that isn't planned for 0.x.

## 6. Filesystem tools on Android ABIs

Android doesn't ship tools for exFAT, NTFS, Btrfs or XFS, and an app can't use the system's tools
without root. So filesystems the app creates without root are written by the app's own code:
**FAT32, exFAT and Linux swap** (plus label changes for FAT, exFAT, ext2/3/4 and swap). Those are
checked in CI against `fsck.vfat`, `fsck.exfat`, `blkid`, `sgdisk --verify` and by mounting them.

Bundling `mke2fs`/`e2fsck` (from e2fsprogs) for arm64-v8a and x86_64 is feasible via the NDK and
would bring ext4 creation to non-root USB drives through a block-device shim; it's on the roadmap,
not in 0.1.

## 7. How USB storage shows up on Android

When nothing else claims it, the kernel's `usb-storage` (or `uas`) driver binds the device, vold
reads its partition table and mounts the first supported filesystem (FAT, exFAT, and on some
phones NTFS) under `/mnt/media_rw/<UUID>`, exposed to apps at `/storage/<UUID>`. The
`StorageVolume` UUID is the filesystem serial, which the app matches against what it reads from
the drive to tell which partition Android had mounted.

USB-to-SATA and USB-to-NVMe enclosures look exactly like flash drives at this level: a SCSI disk
behind Bulk-Only or UAS. The bridge decides what else passes through.

## 8. Feasibility by environment

| | Stock Android | Rooted Android | Linux handheld |
|---|---|---|---|
| USB drives (Bulk-Only): partition, format FAT32/exFAT/swap, label, erase | Yes (0.1) | Yes (0.1) | Use native tools |
| USB drives: ext4/F2FS create, check, resize | No | Planned (root tools) | Native |
| UAS-only enclosures | No | Planned (root, /dev/block) | Native |
| SD card slot | Info only | Planned (root) | Native |
| Phone's internal storage | Info only | Read-only by policy | n/a |
| SMART over USB-SATA bridges | Yes (0.1) | Yes | Native |
| NVMe health over USB bridges | Not yet | Not yet | Native |
| Secure erase (ATA/NVMe sanitize) | Not offered | Not offered | Native |
