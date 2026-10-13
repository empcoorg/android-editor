# workspace/

Your Android projects live here, and this directory is mounted at `/workspace`
inside the dev container. It is **git-ignored**, so device backups, per-device
diagnostic scripts and personal projects never end up in the repository.

Subdirectories the tooling uses:

- `tools/`      helper checkouts (e.g. mtkclient)
- `backups/`    where `android-full-backup` writes
- `backgrounds/` images
