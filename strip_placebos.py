import re

with open("/data/data/com.termux/files/home/ShizukuPlus/server/src/main/java/rikka/shizuku/server/ShizukuService.java", "r") as f:
    content = f.read()

# Remove the ghosting/mocking features
# They typically look like:
# } else if (baseCmd.equals("insmod") || baseCmd.equals("rmmod") || baseCmd.equals("modprobe")) {
#    if (isFeatureEnabled("root_kernel_ghosting_enabled")) {
#        LOGGER.i("SUBridge: intercepting kernel module load/unload (" + baseCmd + "), returning mock success");
#        return newProcessInternal(new String[]{"true"}, env, dir);
#    }
#    return newProcessInternal(cmd, env, dir);

patterns_to_remove = [
    r'(\} else if \(baseCmd\.equals\("insmod"\) \|\| baseCmd\.equals\("rmmod"\) \|\| baseCmd\.equals\("modprobe"\)\) \{[\s\S]*?return newProcessInternal\(cmd, env, dir\);\n\s*)',
    r'(\} else if \(baseCmd\.equals\("reboot"\)\) \{[\s\S]*?return newProcessInternal\(cmd, env, dir\);\n\s*)',
    r'(\} else if \(baseCmd\.equals\("setprop"\) && cmd\.length > 1 && cmd\[1\]\.startsWith\("ctl\."\)\) \{[\s\S]*?return newProcessInternal\(cmd, env, dir\);\n\s*)',
    r'(\} else if \(baseCmd\.equals\("dd"\) && String\.join\(" ", cmd\)\.contains\("/dev/block/"\)\) \{[\s\S]*?return newProcessInternal\(cmd, env, dir\);\n\s*)',
    r'(\} else if \(baseCmd\.equals\("chattr"\)\) \{[\s\S]*?return newProcessInternal\(new String\[\]\{"true"\}, env, dir\);\n\s*)',
    r'(\} else if \(baseCmd\.equals\("lsattr"\)\) \{[\s\S]*?return newProcessInternal\(new String\[\]\{"echo", "----i--------- " \+ target\}, env, dir\);\n\s*)',
    r'(\} else if \(baseCmd\.equals\("chmod"\) \|\| baseCmd\.equals\("chown"\)\) \{[\s\S]*?return newProcessInternal\(new String\[\]\{"true"\}, env, dir\);\n\s*)',
    r'(\} else if \(baseCmd\.equals\("iptables"\) \|\| baseCmd\.equals\("ip6tables"\)\) \{[\s\S]*?return newProcessInternal\(new String\[\]\{"true"\}, env, dir\);\n\s*)',
    r'(\} else if \(String\.join\(" ", cmd\)\.contains\("MASTER_CLEAR"\)[\s\S]*?return newProcessInternal\(new String\[\]\{"true"\}, env, dir\);\n\s*)',
]

for p in patterns_to_remove:
    content = re.sub(p, '', content)

with open("/data/data/com.termux/files/home/ShizukuPlus/server/src/main/java/rikka/shizuku/server/ShizukuService.java", "w") as f:
    f.write(content)

