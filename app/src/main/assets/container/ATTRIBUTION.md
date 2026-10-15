# Bundled Linux container helpers

These binaries are bundled so the Linux Environment works fully offline
except for the Alpine rootfs download. Total ~300 KB per ABI.

## proot (user-mode chroot / bind-mount engine, no root required)

- Version: 5.1.107.96 (aarch64 + x86_64, Android NDK builds)
- Source: Termux package repository
  - https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_aarch64.deb
  - https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.96_x86_64.deb
- Upstream: https://github.com/termux/proot (patches for Android),
  original project https://proot-me.github.io/
- License: GNU GPL v2 or later (runs as a separate process; it does not
  link into RaikaAI code)

## libtalloc (proot dependency)

- Version: 2.5.0
- Source: https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/
- Upstream: https://talloc.samba.org/
- License: GNU LGPL v3 (shared library, dynamically linked at runtime)

## libandroid-shmem (proot dependency, SysV shm shim for Android)

- Version: 0.7
- Source: https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/
- Upstream packaging: https://github.com/termux/termux-packages (see package
  page for license text)

## Reproducing

1. Download the three `.deb` files above for the wanted arch.
2. `ar x package.deb` then `tar -xf data.tar.xz`.
3. Copy `usr/bin/proot`, `usr/lib/libtalloc.so.2`,
   `usr/lib/libandroid-shmem.so`, `usr/libexec/proot/loader`,
   `usr/libexec/proot/loader32` into `container/<abi>/`
   (rename to flat names: `proot`, `libtalloc.so.2`,
   `libandroid-shmem.so`, `loader`, `loader32`).
4. Update the pinned SHA-256 hashes in
   `app/src/main/java/com/raikaai/app/core/container/ContainerManifest.kt`.
