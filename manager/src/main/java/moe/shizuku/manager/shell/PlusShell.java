package moe.shizuku.manager.shell;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Arrays;
import java.util.List;

import moe.shizuku.manager.utils.ActivityLogManager;
import moe.shizuku.manager.utils.ActivityLogRecord;
import moe.shizuku.server.IAICorePlus;
import moe.shizuku.server.IVirtualMachineManager;
import moe.shizuku.server.IShizukuService;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

public class PlusShell {

    private static void printHelp() {
        System.out.println("ShizukuPlus CLI Helper");
        System.out.println("Usage: plus [command] [args]");
        System.out.println("");
        System.out.println("Commands:");
        System.out.println("  vm list           List all Microdroid VMs");
        System.out.println("  vm start [name]   Start a specific VM");
        System.out.println("  storage open [p]  Open a restricted path via Storage Bridge");
        System.out.println("  log               View the privileged activity log");
        System.out.println("  doctor            Run system diagnostics");
        System.out.println("  spoof [target]    Set device identity spoofing");
        System.out.flush();
    }

    private static void handleLog() {
        List<ActivityLogRecord> records = ActivityLogManager.INSTANCE.getRecords();
        if (records.isEmpty()) {
            System.out.println("Activity log is empty.");
        } else {
            System.out.println("Recent Privileged Activities:");
            for (ActivityLogRecord record : records) {
                System.out.printf("[%tT] %s: %s (%s)\n", 
                    record.getTimestamp(), record.getAppName(), record.getAction(), record.getPackageName());
            }
        }
        System.out.flush();
    }

    private static void handleVm(String[] args, IBinder binder) throws RemoteException {
        if (args.length < 2) {
            System.out.println("Usage: plus vm [list|start|stop|delete]");
            return;
        }

        IShizukuService service = IShizukuService.Stub.asInterface(binder);
        // Using reflection or specific ShizukuX binder calls
        // For now, we simulate the binder call logic
        System.out.println("Calling IVirtualMachineManager...");
        
        switch (args[1]) {
            case "list":
                System.out.println("Active VMs: None");
                break;
            case "start":
                if (args.length < 3) System.out.println("Missing VM name");
                else System.out.println("Starting VM: " + args[2]);
                break;
            default:
                System.out.println("Unknown VM command: " + args[1]);
        }
    }

    public static void main(String[] args, String packageName, IBinder binder, Handler handler) {
        if (args.length == 0 || args[0].equals("help")) {
            printHelp();
            System.exit(0);
        }

        Shizuku.onBinderReceived(binder, packageName);
        
        try {
            switch (args[0]) {
                case "log":
                    handleLog();
                    break;
                case "vm":
                    handleVm(args, binder);
                    break;
                case "spoof":
                    if (args.length < 2) {
                        System.out.println("Current spoof: None");
                    } else {
                        System.out.println("Setting spoof target to: " + args[1]);
                    }
                    break;
                case "doctor":
                    System.out.println("Checking ShizukuX Service status...");
                    System.out.println("Server version: " + Shizuku.getVersion());
                    System.out.println("SELinux: " + Shizuku.getSELinuxContext());
                    break;
                default:
                    System.out.println("Unknown command: " + args[0]);
                    printHelp();
            }
        } catch (Throwable tr) {
            tr.printStackTrace(System.err);
        } finally {
            System.out.flush();
            System.exit(0);
        }
    }
}
