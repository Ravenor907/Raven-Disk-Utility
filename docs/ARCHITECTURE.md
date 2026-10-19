# Architecture

Two Gradle modules with a hard line between them.

```
core/   plain Kotlin, no Android: everything that touches bytes on a disk
app/    Android: USB plumbing, platform info, the foreground service and the Compose UI
```

`core` runs in ordinary JVM unit tests, so the code that writes partition tables and
filesystems is tested on simulated drives (and in CI against the real Linux tools) before any of
it reaches a phone.

## core

| Package | What it does |
|---|---|
| `io` | `BlockDevice` (the only thing the rest of core writes through), Bulk-Only Transport and SCSI (`Scsi.kt`), in-memory, sparse and file-backed disks |
| `table` | GPT and MBR reading (CRCs, backup GPT fallback, EBR chains, hybrid and damaged detection) and writing |
| `fs` | Filesystem probes (FAT, exFAT, NTFS, ext2/3/4, Btrfs, XFS, F2FS, UDF, swap, ISO 9660, LUKS, BitLocker, HFS+, APFS, LVM, RAID), FAT32/exFAT/swap formatters, label editors, and `FsSupport`, the support matrix the UI shows |
| `health` | ATA IDENTIFY and SMART through SAT, parsed into a report that says "unavailable" instead of guessing |
| `model` | `DiskInfo` and `Fact<T>`: every property is *detected* (with its source), *inferred* (with the reason) or *unavailable* (with the reason) |
| `inspect` | Reads a device's table and probes every partition. Read-only |
| `plan` | `Planner` (validates a queue of operations against a snapshot and computes the resulting layout), `Executor` (runs it), `TextJournal` |

### Operations are plans, not commands

Nothing in the UI writes to a disk directly. Every button builds an `Op` and asks the `Planner`
whether it's valid against the layout the queue would produce so far. Invalid operations come
back with a reason, and the button shows that reason instead of acting. The person reviews the
whole queue, then the `Executor` carries it out:

1. **Identity check.** Re-reads the drive (size, model, serial, and a CRC of the blocks where
   tables live) and refuses to write if anything differs from the snapshot the plan was made on.
2. **Preflight.** Every filesystem layout and the table image are computed before the first
   write, so a plan that can't be carried out fails with nothing written.
3. **Erase, format, relabel**, inside partition ranges.
4. **Partition table last**: backup GPT before primary, each array before its header.
5. **Flush**, then **verify** by reading the table back and probing each changed filesystem.

There's no rollback, and the app never claims one. If a step fails or the person stops, the
result lists exactly what was written. A journal entry is opened before the first write and
closed after the last; an entry that was never closed is reported on the next launch.

### Safety rules enforced in code

- Commands are never built from strings. Device identifiers come from Android's own device list,
  and every operation is a typed value checked by the planner (bounds, overlaps, alignment to
  the physical sector, 1 MiB minimum, MBR's 2 TiB limit, filesystem size limits, label rules).
- Partitions Android has mounted, write-protected drives, damaged tables and logical MBR
  partitions are refused with a reason.
- Discovery and refresh only read. The app writes only from the executor, after review.
- The operation log records model and size but not serial numbers.

## app

| Package | What it does |
|---|---|
| `backend` | `UsbBackend` (claims Bulk-Only interfaces, multiple LUNs for card readers, UAS detection), `Platform` (Android volumes, USB host, su and tool detection), `Drives` (the dashboard's state, sessions and plans) |
| `run` | `Runner` runs one plan at a time off the main thread; `OpService` is the foreground service that keeps the process and CPU alive meanwhile |
| `ui` | Compose (foundation only, no Material), Handheld Forge's design language with Raven's own accent |

New backends (root, a desktop front end) plug in by producing `DiskInfo` and a `DiskSession`;
the planner, executor and UI don't change.
