#!/bin/bash
# Package the official Alpine Linux aarch64 minirootfs as an app asset.
#
# Downloads the current minirootfs release from the pinned branch, verifies
# it against the CDN's .sha256 sidecar, and repackages it from tar.gz to
# tar.xz under the asset naming scheme the app discovers
# (<id>_<version>_aarch64_rootfs.tar.xz — see UserlandDistro.java). The
# image content is left untouched: DNS/hosts defaults are written by the
# in-app installer, and everything else (apk, BusyBox, CA bundle) ships in
# the minirootfs already. Only the aarch64 image is ever packaged —
# arm64chroot emulates an AArch64 guest on every host ABI.
#
# Needs curl, sha256sum, gzip and xz; no root.
set -eo pipefail

ALPINE_BRANCH=latest-stable
MIRROR="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}/releases/aarch64"

ROOTFS_STORAGE=$(dirname "$(realpath "$0")")/../UserlandRootfs
mkdir -p "$ROOTFS_STORAGE"

# Discover the exact minirootfs file name (it carries the version).
# Matched in-shell rather than piped through grep | head: under pipefail, an
# early-exiting head can fail the whole pipeline on a broken pipe.
RELEASES=$(curl -fsSL "${MIRROR}/latest-releases.yaml")
FILE=
if [[ $RELEASES =~ alpine-minirootfs-[0-9][0-9.]*-aarch64\.tar\.gz ]]; then
	FILE=${BASH_REMATCH[0]}
fi
if [ -z "$FILE" ]; then
	echo "error: no minirootfs entry found in ${MIRROR}/latest-releases.yaml" >&2
	exit 1
fi
VERSION=${FILE#alpine-minirootfs-}
VERSION=${VERSION%-aarch64.tar.gz}

WORKDIR=$(mktemp -d)
# Also drops a half-written ${OUT}.tmp when a step fails (OUT is set below).
trap 'rm -rf "$WORKDIR" ${OUT:+"$OUT.tmp"}' EXIT

echo "Fetching ${FILE} (Alpine ${VERSION})..."
curl -fL -o "${WORKDIR}/${FILE}" "${MIRROR}/${FILE}"
curl -fsSL -o "${WORKDIR}/${FILE}.sha256" "${MIRROR}/${FILE}.sha256"
(cd "$WORKDIR" && sha256sum -c "${FILE}.sha256")

OUT="${ROOTFS_STORAGE}/alpine_${VERSION}_aarch64_rootfs.tar.xz"
echo "Repacking to ${OUT}..."
# gzip -dc, not zcat: macOS zcat only reads .Z files, and pipefail makes
# a failed decompress stop the script instead of packaging an empty image.
gzip -dc "${WORKDIR}/${FILE}" | xz -9 -T0 > "${OUT}.tmp"
mv "${OUT}.tmp" "$OUT"
echo "Done."
