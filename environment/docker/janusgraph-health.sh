#!/usr/bin/env bash
set -e
exec 3<>/dev/tcp/127.0.0.1/8182
exec 3>&-
