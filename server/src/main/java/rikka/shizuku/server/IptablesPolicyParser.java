package rikka.shizuku.server;

/**
 * Pure parser for the SU-bridge iptables → NetworkPolicy mapping (see
 * ShizukuService.mapIptablesToNetworkPolicy). Kept side-effect free and unit-testable:
 * extract the uid and the restrict/unrestrict intent from an iptables argv array.
 *
 * Returns null when the command is not a uid-owner iptables rule that can be mapped.
 */
final class IptablesPolicyParser {

    static final class Policy {
        final int uid;
        final boolean restricted;

        Policy(int uid, boolean restricted) {
            this.uid = uid;
            this.restricted = restricted;
        }
    }

    private IptablesPolicyParser() {
    }

    /** Parses `iptables ... --uid-owner <uid> ...` into (uid, restricted). */
    static Policy parse(String[] cmd) {
        if (cmd == null) return null;
        String fullCmd = String.join(" ", cmd);
        if (!fullCmd.contains("--uid-owner")) return null;

        int index = -1;
        for (int i = 0; i < cmd.length; i++) {
            if (cmd[i].equals("--uid-owner")) {
                index = i + 1;
                break;
            }
        }
        if (index == -1 || index >= cmd.length) return null;

        int uid;
        try {
            uid = Integer.parseInt(cmd[index]);
        } catch (NumberFormatException e) {
            return null;
        }
        // -A/-I add a rule (restrict); -D deletes it (unrestrict).
        boolean restricted = !fullCmd.contains("-D");
        return new Policy(uid, restricted);
    }
}
