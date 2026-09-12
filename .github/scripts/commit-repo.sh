#!/bin/bash
set -e

rsync -a --delete --exclude .git --exclude .gitignore ../master/repo/ .
git config --global user.email "github-actions[bot]@users.noreply.github.com"
git config --global user.name "github-actions[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push origin HEAD:repo

    # Purge cached index on jsDelivr if repository is set
    if [ -n "$GITHUB_REPOSITORY" ]; then
        curl -s "https://purge.jsdelivr.net/gh/${GITHUB_REPOSITORY}@repo/index.min.json" || true
    fi
else
    echo "No changes to commit"
fi
