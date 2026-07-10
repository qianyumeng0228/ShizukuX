#!/bin/bash
# Installs the pre-push guard as a real git pre-push hook so pushes from any
# tool (not just AI-session file edits) are gated. Re-runnable.
# The hook lives under $HOME because /sdcard filesystems cannot set the
# executable bit git requires; core.hooksPath points git at it.
cd "$(dirname "$0")/.."
HOOK_DIR="$HOME/.githooks/shizukuplus"
mkdir -p "$HOOK_DIR"
cat > "$HOOK_DIR/pre-push" <<'HOOK'
#!/bin/bash
# Auto-installed by pre-push-guard/install-hooks.sh
SKIP_GRADLE_CHECK=1 bash "$(git rev-parse --show-toplevel)/pre-push-guard/scripts/pre_push_check.sh"
HOOK
chmod +x "$HOOK_DIR/pre-push"
git config core.hooksPath "$HOOK_DIR"
rm -f .git/hooks/pre-push
echo "pre-push hook installed at $HOOK_DIR (core.hooksPath set)."
