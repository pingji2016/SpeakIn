#!/bin/bash
cat > /etc/wsl.conf << 'EOF'
[user]
default=pingji
[automount]
root = /mnt/host
options = "metadata"
[interop]
enabled = true
EOF
cat /etc/wsl.conf
