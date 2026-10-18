# immich-frame-feeder

A lightweight Kotlin Native daemon that syncs photos from a designated [Immich](https://immich.app/) album to digital photo frames (such as [Frameo](https://frameo.net/)) running Termux via SSH/SCP.

## Features

- **Automated Album Synchronization**: Continuously polls a specified Immich album for new photos.
- **Image Optimization**: Automatically resizes and re-compresses photos using ImageMagick (`convert`) to match target display resolution and save bandwidth.
- **Multi-Device Support**: Broadcasts photos to multiple Frameo devices defined in your SSH config.
- **Automated Housekeeping**: Automatically cleans up photos from the frames once they are removed from the Immich album.
- **Lightweight Native Binary**: Compiles to a self-contained Kotlin/Native Linux x86_64 binary with minimal memory and CPU footprint.

## How It Works

1. Queries the Immich API (`/api/albums/{id}`) using your API key.
2. Lists existing images on Frameo devices over SSH (`ls /data/data/com.termux/files/home/storage/dcim/`).
3. For any new photo in the album:
   - Locates the original file from the local library mount (`/data/<originalPath>`).
   - Resizes and compresses with ImageMagick (`convert -resize 1920x1280^ -quality 99%`).
   - Copies it to all configured Frameo devices via SCP.
4. If photos were removed from the Immich album, executes `rm` over SSH on each device to remove them.
5. Sleeps for the remainder of the 60-second cycle and repeats.

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
```

> **Note**: Host names in `config` must start with `frameo` to be detected by the feeder.

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
```

### 3. Run with Docker Compose

```bash
docker compose up -d --build
```

## Building from Source

Requirements:
- JDK 17+
- Kotlin Multiplatform / Native toolchain

```bash
./gradlew linkReleaseExecutableNative
```
The compiled binary will be placed at `build/bin/native/releaseExecutable/KotlinNativeTemplate.kexe`.
