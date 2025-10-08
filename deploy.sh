#!/usr/bin/env bash
set -euo pipefail
/snap/bin/hugo --minify
sudo rsync -av --delete public/ /var/www/blog/
sudo chown -R www-data:www-data /var/www/blog
sudo systemctl reload nginx
