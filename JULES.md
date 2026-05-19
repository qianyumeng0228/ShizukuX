# Jules Guidelines

Jules is an autonomous coding agent from Google. In this project (especially on Termux/Android), specific configurations are required to ensure stable operation.

## Termux Setup
The `jules` CLI is installed globally via `pnpm` with a specialized workaround.
- **Binary:** Uses the `linux-arm64` binary (statically linked).
- **Wrapper:** Executed via `proot` to bypass Android kernel restrictions on `faccessat2` and other syscalls.
- **Environment:**
    - `GODEBUG=asyncpreemptoff=1` is set to prevent `proot` signal hangs.
    - `DBUS_SESSION_BUS_ADDRESS=''` is set to avoid DBus discovery delays.
    - `/etc/resolv.conf` and CA certificates are bound for network/TLS support.

## Common Commands
- `jules new "description"`: Create a new session for the current working directory.
- `jules new --repo owner/repo "description"`: Create a session for a specific GitHub repository (preferred for remote tasks).
- `jules new --parallel 3 "description"`: Create multiple parallel sessions to explore different solutions.
- `jules remote list --session`: List all active remote sessions.
- `jules login --no-launch-browser`: Authenticate using a manual code (required for Termux).

## Development Workflow
1. **Trigger:** Assign tasks via CLI or by adding the `jules` label to a GitHub issue.
2. **Verification:** Jules executes in a secure Cloud VM, running tests before proposing changes.
3. **Delivery:** Jules creates a branch and a Pull Request on GitHub for review.

## Known Issues in Termux
- **CLI Hangs:** After printing output, the `jules` process may not exit cleanly due to `proot`. You may need to `Ctrl+C` to return to the prompt.
- **TLS/DNS:** Handled by the custom wrapper; do not remove the bindings in `run.cjs`.
