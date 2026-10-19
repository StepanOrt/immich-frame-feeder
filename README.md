# immich-frame-feeder

A lightweight Kotlin Native daemon that syncs photos from a designated [Immich](https://immich.app/) album to digital photo frames (such as [Frameo](https://frameo.net/)) running Termux via SSH/SCP.

## Features

- **Automated Album Synchronization**: Continuously polls a specified Immich album for new photos.
- **Image Optimization**: Automatically resizes and re-compresses photos using ImageMagick (`convert`) to match target display resolution and save bandwidth.
- **Multi-Device Support**: Broadcasts photos to multiple Frameo devices defined in your SSH config.
- **Automated Housekeeping**: Automatically cleans up photos from the frames once they are removed from the Immich album.
- **Lightweight Native Binary**: Compiles to a self-contained Kotlin/Native Linux x86_64 binary with minimal memory and CPU footprint.

## How It Works

1. Queries the Immich API (`/api/search/metadata`) using your API key and album ID.
2. Lists existing images on Frameo devices over SSH (`ls /data/data/com.termux/files/home/storage/dcim/`).
3. For any new photo in the album:
   - Locates the original file from the local library mount (`/data/<originalPath>`).
   - Resizes and compresses with ImageMagick (`convert -resize 1920x1280^> -quality 99%`).
   - Copies it to all configured Frameo devices via SCP.
4. If photos were removed from the Immich album, executes `rm` over SSH on each device to remove them.
5. Sleeps for the remainder of the configured polling interval (`POLL_INTERVAL_SECONDS`, default 60s) and repeats.

## Setup & Deployment

### 1. Configure SSH Access for Frameo Devices

Frameo frames run Android. With [Termux](https://termux.dev/) installed on each frame, enable SSH (`sshd`) and set up SSH key authentication.

Create `ssh_config/config` based on `ssh_config/config.example`:

```ssh-config
Host frameo_living_room
    User u0_a100
    HostName 192.168.1.50
    Port 8022
    IdentityFile ~/.ssh/id_frameo
    ConnectTimeout 5

Host frameo_bedroom
    User u0_a100
    HostName 192.168.1.51
    Port 8022
    IdentityFile ~/.ssh/id_frameo
    ConnectTimeout 5

Host frameo*
    StrictHostKeyChecking yes
    UserKnownHostsFile ~/.ssh/known_hosts
    ConnectTimeout 10
```

> **Note**: Host names in `config` must start with `frameo` to be detected by the feeder.
> Because `./ssh_config` is mounted read-only (`:ro`), scan each frame's host key into `ssh_config/known_hosts` ahead of time to enable strict verification:
> ```bash
> ssh-keyscan -p 8022 192.168.1.50 192.168.1.51 >> ssh_config/known_hosts
> ```
>
> **Permissions & Non-Root User**: The container runs as non-root user `appuser` (UID 1000). Ensure the mounted files are readable by UID 1000:
> ```bash
> chmod 600 ssh_config/id_frameo
> chmod 644 ssh_config/config ssh_config/known_hosts
> # If your host user UID is not 1000:
> chown -R 1000:1000 ssh_config/
> ```

### 2. Configure Environment

Copy `.env.example` to `.env` and set your credentials:

```bash
cp .env.example .env
```

```env
IMMICH_URL=https://immich.example.com
IMMICH_ALBUM_ID=2ca2947a-80cd-43ca-8b45-4aa14b0aae2b
IMMICH_API_KEY=your-api-key-here
IMMICH_LIBRARY_PATH=/mnt/pool/immich/library
FOTO_LIBRARY_PATH=/mnt/pool/foto
RESOLUTION=1920x1280
JPEG_QUALITY=99%
POLL_INTERVAL_SECONDS=60

# Optional safety overrides (default false)
ALLOW_EMPTY_ALBUM=false
ALLOW_MASS_DELETE=false
```

### 3. Run with Docker Compose

To pull and run using the published GitHub Container Registry image:

```bash
docker compose up -d
```

If running directly with `docker run`, pass `--init` so zombie processes and signals are reaped cleanly:
```bash
docker run -d --init --name frameo ...
```

Or build and run locally from source:

```bash
./gradlew linkReleaseExecutableNative
cp build/bin/native/ImmichFrameFeederReleaseExecutable/ImmichFrameFeeder.kexe ./ImmichFrameFeeder.kexe
docker compose up -d --build
```

## Building from Source

Requirements:
- JDK 21+ (CI uses JDK 25)
- Kotlin Multiplatform / Native toolchain

```bash
./gradlew linkReleaseExecutableNative
```
The compiled binary will be placed at `build/bin/native/ImmichFrameFeederReleaseExecutable/ImmichFrameFeeder.kexe`.
