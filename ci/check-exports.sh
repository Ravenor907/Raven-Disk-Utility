#!/usr/bin/env bash
# Checks the disk images Raven's own code made (RealToolsTest.exportImagesForRealTools) with the
# real Linux tools: sgdisk/sfdisk for the tables, fsck for the filesystems, blkid for labels, and
# a real mount + write + fsck again. Needs root for loop devices and mounting.
set -uo pipefail
dir=${1:?usage: check-exports.sh DIR}
cd "$dir"
fail=0
bad() { echo "::error title=Image check::$*"; fail=1; }
ok() { echo "ok: $*"; }

# Partition tables
for img in gpt.img edit.img; do
  out=$(sgdisk -v "$img" 2>&1)
  if grep -q "No problems found" <<<"$out"; then ok "$img: sgdisk --verify clean"; else bad "$img: sgdisk --verify: $out"; fi
done
sgdisk -p gpt.img
sfdisk -d mbr.img || bad "mbr.img: sfdisk can't read the MBR"
grep -q 'bootable' <(sfdisk -d mbr.img) && ok "mbr.img: partition 1 is active" || bad "mbr.img: active flag missing"

declare -A loops
attach() { # image [sector size]
  local args=(-f --show -P)
  [ -n "${2:-}" ] && args+=(-b "$2")
  loops[$1]=$(sudo losetup "${args[@]}" "$1")
  sudo partprobe "${loops[$1]}" 2>/dev/null || true
  sleep 1
}
attach gpt.img
attach mbr.img
attach edit.img
attach gpt4k.img 4096
attach ext4.img
lsblk -o NAME,SIZE,PHY-SEC,LOG-SEC,FSTYPE,LABEL,PARTLABEL,PARTTYPE "${loops[@]}" || true

# Labels and types as blkid sees them, against what the app meant to make.
while IFS=$'\t' read -r img part type label; do
  dev="${loops[$img]}p$part"
  got_type=$(sudo blkid -p -s TYPE -o value "$dev")
  got_label=$(sudo blkid -p -s LABEL -o value "$dev")
  [ "$got_type" = "$type" ] && [ "$got_label" = "$label" ] && ok "$img#$part: $type '$label'" || bad "$img#$part: blkid says '$got_type' '$got_label', expected '$type' '$label'"
  case "$type" in
    vfat) out=$(sudo fsck.vfat -n "$dev" 2>&1); [ $? -eq 0 ] && ok "$img#$part: fsck.vfat clean" || bad "$img#$part: fsck.vfat: $out" ;;
    exfat) out=$(sudo fsck.exfat -n "$dev" 2>&1); [ $? -eq 0 ] && ok "$img#$part: fsck.exfat clean" || bad "$img#$part: fsck.exfat: $out" ;;
    ext4) out=$(sudo e2fsck -fn "$dev" 2>&1); [ $? -eq 0 ] && ok "$img#$part: e2fsck clean" || bad "$img#$part: e2fsck: $out" ;;
    swap) sudo swaplabel "$dev" | grep -q "LABEL: $label" && ok "$img#$part: swaplabel agrees" || bad "$img#$part: swaplabel disagrees" ;;
  esac
done < expect.tsv

# GPT names (PARTLABEL) and types the app wrote.
[ "$(sudo blkid -p -s PART_ENTRY_NAME -o value "${loops[gpt.img]}p1")" = "EFI system" ] && ok "gpt.img#1 named 'EFI system'" || bad "gpt.img#1 name wrong"
[ "$(sudo blkid -p -s PART_ENTRY_TYPE -o value "${loops[gpt.img]}p1")" = "c12a7328-f81f-11d2-ba4b-00a0c93ec93b" ] && ok "gpt.img#1 is an EFI system partition" || bad "gpt.img#1 type wrong"
[ "$(sudo blkid -p -s PART_ENTRY_NAME -o value "${loops[edit.img]}p1")" = "renamed" ] && ok "edit.img#1 renamed" || bad "edit.img#1 rename missing"

# Mount, write, unmount, check again: the filesystems work, not just parse.
mnt=$(mktemp -d)
for spec in "gpt.img 1" "gpt.img 2" "mbr.img 1" "mbr.img 2" "gpt4k.img 1" "gpt4k.img 2" "edit.img 1" "edit.img 2" "ext4.img 1"; do
  set -- $spec
  dev="${loops[$1]}p$2"
  if sudo mount "$dev" "$mnt"; then
    head -c 3000000 /dev/urandom | sudo tee "$mnt/test.bin" >/dev/null
    sum1=$(sudo sha256sum "$mnt/test.bin" | cut -d' ' -f1)
    sudo mkdir -p "$mnt/a/b/c" && echo hello | sudo tee "$mnt/a/b/c/hello.txt" >/dev/null
    sudo umount "$mnt"
    sudo mount "$dev" "$mnt"
    sum2=$(sudo sha256sum "$mnt/test.bin" | cut -d' ' -f1)
    sudo umount "$mnt"
    [ "$sum1" = "$sum2" ] && ok "$1#$2: mounted, wrote 3 MB and read it back" || bad "$1#$2: data changed after remount"
    t=$(sudo blkid -p -s TYPE -o value "$dev")
    if [ "$t" = vfat ]; then out=$(sudo fsck.vfat -n "$dev" 2>&1) || bad "$1#$2: fsck.vfat after writing: $out"; fi
    if [ "$t" = ext4 ]; then out=$(sudo e2fsck -fn "$dev" 2>&1) || bad "$1#$2: e2fsck after writing: $out"; fi
    if [ "$t" = exfat ]; then out=$(sudo fsck.exfat -n "$dev" 2>&1) || bad "$1#$2: fsck.exfat after writing: $out"; fi
  else
    bad "$1#$2: the kernel wouldn't mount it"
  fi
done
sudo swapon "${loops[gpt.img]}p3" && ok "gpt.img#3: kernel accepted the swap area" && sudo swapoff "${loops[gpt.img]}p3" || bad "gpt.img#3: swapon failed"

for l in "${loops[@]}"; do sudo losetup -d "$l"; done
exit $fail
