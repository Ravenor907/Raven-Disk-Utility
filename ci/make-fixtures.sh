#!/usr/bin/env bash
# Makes filesystem images with the real Linux tools, plus expect.tsv with what blkid says about
# each one (image, partition or 0, TYPE, LABEL, UUID). RealToolsTest checks that the app's reader
# agrees. Tools that aren't installed are skipped, so this also runs on a dev machine.
set -Eeuo pipefail
# The job log isn't always easy to reach, so every failure also becomes an annotation.
trap 'echo "::error title=make-fixtures line $LINENO (${FUNCNAME[0]:-main})::$BASH_COMMAND failed"' ERR
out=${1:?usage: make-fixtures.sh DIR}
mkdir -p "$out"
cd "$out"
: > expect.tsv

have() { command -v "$1" >/dev/null 2>&1; }

# blkid's view of a whole image or of a byte offset inside one.
record() { # image part offset
  local img=$1 part=$2 off=${3:-0}
  local vals
  vals=$(blkid -p -O "$off" -o export "$img" 2>/dev/null || true)
  local type label uuid
  type=$(sed -n 's/^TYPE=//p' <<<"$vals")
  label=$(sed -n 's/^LABEL=//p' <<<"$vals" | sed 's/\\ / /g')
  uuid=$(sed -n 's/^UUID=//p' <<<"$vals")
  if [ -z "$type" ]; then echo "::error title=Fixture::blkid saw nothing in $img#$part"; return 1; fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$img" "$part" "$type" "$label" "$uuid" >> expect.tsv
  echo "made $img#$part: $type '$label' $uuid"
}

img() { rm -f "$1"; truncate -s "$2" "$1"; }

if have mkfs.ext4; then
  img ext2.img 64M; mkfs.ext2 -q -F -L "old ext2" ext2.img; record ext2.img 0
  img ext3.img 64M; mkfs.ext3 -q -F -L journaled ext3.img; record ext3.img 0
  img ext4.img 128M; mkfs.ext4 -q -F -L "Linux Root" -O metadata_csum,64bit ext4.img; record ext4.img 0
fi
if have mkswap; then img swap.img 32M; mkswap -L myswap swap.img >/dev/null 2>&1; record swap.img 0; fi
if have mkfs.vfat; then
  img fat16.img 64M; mkfs.vfat -F 16 -n FAT16VOL fat16.img >/dev/null; record fat16.img 0
  img fat32.img 256M; mkfs.vfat -F 32 -n "MY STICK" fat32.img >/dev/null; record fat32.img 0
fi
if have mkfs.exfat; then img exfat.img 256M; mkfs.exfat -L "exFAT Vol" exfat.img >/dev/null; record exfat.img 0; fi
if have mkfs.ntfs; then img ntfs.img 64M; mkfs.ntfs -q -F -f -L "Windows Data" ntfs.img >/dev/null; record ntfs.img 0; fi
if have mkfs.btrfs; then img btrfs.img 256M; mkfs.btrfs -q -L "btrfs pool" btrfs.img >/dev/null; record btrfs.img 0; fi
if have mkfs.xfs; then img xfs.img 320M; mkfs.xfs -q -L xfsvol xfs.img; record xfs.img 0; fi
if have mkfs.f2fs; then img f2fs.img 128M; mkfs.f2fs -q -l "f2fs data" f2fs.img >/dev/null; record f2fs.img 0; fi
if have mkudffs; then img udf.img 64M; mkudffs --label="UDF Disc" udf.img >/dev/null; record udf.img 0; fi
if have cryptsetup; then
  img luks.img 32M
  printf 'raven-test' > key
  cryptsetup luksFormat --batch-mode --type luks2 --label "secret stuff" --key-file key --pbkdf pbkdf2 --pbkdf-force-iterations 1000 luks.img
  record luks.img 0
fi
if have xorriso; then
  mkdir -p isotree && echo hi > isotree/hello.txt
  xorriso -as mkisofs -quiet -V RAVEN_ISO -o iso.img isotree; record iso.img 0
fi

# A GPT disk partitioned by sgdisk, with filesystems made by the tools placed inside it.
if have sgdisk && have mkfs.vfat && have mkfs.ext4; then
  img multi.img 512M
  # Explicit sectors: 1 MiB aligned, last partition to the end of the usable area.
  sgdisk -o -n 1:2048:206847 -t 1:ef00 -c 1:"EFI system" -n 2:206848:616447 -t 2:8300 -c 2:root \
    -n 3:616448:1048542 -t 3:0700 multi.img >/dev/null
  pt=$(blkid -p -s PTTYPE -o value multi.img || true)
  [ "$pt" = gpt ] || { echo "::error title=Fixture::sgdisk didn't leave a GPT on multi.img (blkid: '$pt'): $(sgdisk -p multi.img 2>&1 | tr '\n' ' ')"; exit 1; }
  put() { # part file start-sector
    dd if="$2" of=multi.img bs=512 seek="$3" conv=notrunc status=none
    rm -f "$2"
    record multi.img "$1" $(($3 * 512))
  }
  img p1 $((204800 * 512)); mkfs.vfat -F 32 -n ESP p1 >/dev/null; put 1 p1 2048
  img p2 $((409600 * 512)); mkfs.ext4 -q -F -L root p2; put 2 p2 206848
  if have mkfs.exfat; then
    img p3 $((432095 * 512)); mkfs.exfat -L Shared p3 >/dev/null; put 3 p3 616448
  fi
  # An MBR disk from sfdisk.
  if have sfdisk; then
    img dos.img 256M
    printf 'label: dos\nstart=2048, size=131072, type=c, bootable\nstart=133120, type=83\n' | sfdisk -q dos.img
    img q1 64M; mkfs.vfat -F 32 -n DOSBOOT q1 >/dev/null
    dd if=q1 of=dos.img bs=512 seek=2048 conv=notrunc status=none; rm -f q1
    record dos.img 1 $((2048 * 512))
    img q2 $(( (524288 - 133120) * 512 )); mkfs.ext4 -q -F -L dosdata q2
    dd if=q2 of=dos.img bs=512 seek=133120 conv=notrunc status=none; rm -f q2
    record dos.img 2 $((133120 * 512))
  fi
fi
echo "$(wc -l < expect.tsv) fixtures"
