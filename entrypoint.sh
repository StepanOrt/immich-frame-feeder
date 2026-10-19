#!/bin/sh
set -e

SSH_SRC=""
if [ -d /config/ssh ] && [ -n "$(ls -A /config/ssh 2>/dev/null)" ]; then
    SSH_SRC="/config/ssh"
elif [ -d /ssh_config ] && [ -n "$(ls -A /ssh_config 2>/dev/null)" ]; then
    SSH_SRC="/ssh_config"
fi

mkdir -p /home/appuser/.ssh 2>/dev/null || true

if [ -w /home/appuser/.ssh ]; then
    if [ -n "$SSH_SRC" ]; then
        find /home/appuser/.ssh -mindepth 1 -delete
        cp -rL "$SSH_SRC"/. /home/appuser/.ssh/
    fi
    if [ "$(id -u)" = "0" ]; then
        chown -R appuser:appuser /home/appuser/.ssh
    fi
    chmod 700 /home/appuser/.ssh
    find /home/appuser/.ssh -type f -exec chmod 600 {} + 2>/dev/null || true
    [ -f /home/appuser/.ssh/config ] && chmod 644 /home/appuser/.ssh/config
    [ -f /home/appuser/.ssh/known_hosts ] && chmod 644 /home/appuser/.ssh/known_hosts
else
    echo "Warning: /home/appuser/.ssh is read-only; mount SSH config at /config/ssh:ro instead for automatic permission setup" >&2
fi

if [ "$(id -u)" = "0" ]; then
    export HOME=/home/appuser USER=appuser LOGNAME=appuser
    exec setpriv --reuid=appuser --regid=appuser --init-groups -- "$@"
else
    exec "$@"
fi
