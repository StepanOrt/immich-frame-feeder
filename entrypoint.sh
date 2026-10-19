#!/bin/sh
set -e

# If running as root (default in container runtime), safely copy SSH keys to appuser home
if [ "$(id -u)" = "0" ]; then
    mkdir -p /home/appuser/.ssh
    if [ -d /config/ssh ] && [ -n "$(ls -A /config/ssh 2>/dev/null)" ]; then
        cp -a /config/ssh/* /home/appuser/.ssh/
    elif [ -d /ssh_config ] && [ -n "$(ls -A /ssh_config 2>/dev/null)" ]; then
        cp -a /ssh_config/* /home/appuser/.ssh/
    fi
    chown -R appuser:appuser /home/appuser/.ssh
    chmod 700 /home/appuser/.ssh
    find /home/appuser/.ssh -type f -exec chmod 600 {} + 2>/dev/null || true
    [ -f /home/appuser/.ssh/config ] && chmod 644 /home/appuser/.ssh/config
    [ -f /home/appuser/.ssh/known_hosts ] && chmod 644 /home/appuser/.ssh/known_hosts
    exec runuser -u appuser -- "$@"
else
    exec "$@"
fi
