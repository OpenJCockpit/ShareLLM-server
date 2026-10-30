#!/bin/sh
# Creates the production secret files used by compose.prod.yaml. Existing files are kept.
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p secrets
chmod 700 secrets

create() {
  if [ -s "secrets/$1" ]; then
    echo "secrets/$1 exists, keeping it"
  else
    openssl rand -hex 32 > "secrets/$1"
    echo "created secrets/$1"
  fi
  # Docker Compose bind-mounts secret files with their host permissions; the containers run as
  # non-root users, so the files must be readable. The 700 directory keeps other host users out.
  chmod 644 "secrets/$1"
}

create sharellm_public_keys
create sharellm_worker_key
create database_password
