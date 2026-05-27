1. **Analyze the Issue**: In `server/src/main/java/rikka/shizuku/server/ShizukuService.java`, there is a method `waitSystemService` that polls for a system service to be available by checking `ServiceManager.getService(name)` in a while loop, sleeping for 1000ms in each iteration.

2. **Optimization**: Android provides `ServiceManager.waitForService(name)` which efficiently blocks until the service is registered using binder driver notifications, without needing a busy-wait/polling loop with `Thread.sleep`. I've verified that `waitForService` is available in the hidden API stubs used by the project.

3. **Implementation**:
   Modify `waitSystemService` in `server/src/main/java/rikka/shizuku/server/ShizukuService.java` to:
   ```java
    private static void waitSystemService(String name) {
        if (ServiceManager.getService(name) != null) {
            return;
        }
        LOGGER.i("service " + name + " is not started, waiting...");
        try {
            ServiceManager.waitForService(name);
        } catch (Exception e) {
            LOGGER.w(e.getMessage(), e);
        }
    }
   ```
   Or better yet, `ServiceManager.waitForService(name)` handles the checking and waiting. The hidden API stub for `waitForService` usually returns an `IBinder` and might not throw an Exception but we should keep it safe.

   Let's check the return type of `ServiceManager.waitForService(name)` via our test file: `android.os.IBinder`.
   So we can rewrite `waitSystemService`:
   ```java
    private static void waitSystemService(String name) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("service " + name + " is not started, waiting...");
                ServiceManager.waitForService(name);
            } catch (Exception e) {
                LOGGER.w(e.getMessage(), e);
            }
        }
    }
   ```
   Wait, if `waitForService` returns the `IBinder`, we don't even need the while loop unless `waitForService` returns null. A simple loop is safe in case `waitForService` returns null due to timeouts or interrupts.
   Actually, `waitForService` internally blocks until it returns the binder.
   ```java
    private static void waitSystemService(String name) {
        if (ServiceManager.getService(name) == null) {
            LOGGER.i("service " + name + " is not started, waiting...");
            ServiceManager.waitForService(name);
        }
    }
   ```
   If we want to keep the exact `while` loop logic for safety (in case it returns null early on some Android versions), we can replace the `Thread.sleep(1000)` with `ServiceManager.waitForService(name)`. Wait, `waitForService` blocks, so if we put it in a while loop, it might block forever if the service is never started. That's the same as the original code though, which would loop forever.

4. **Verify**: Compile the code and run the tests to ensure everything is valid.

5. **Pre commit**: Follow the `pre_commit_instructions`.

6. **Submit**: Provide a PR explaining the measured / theoretical improvement (event-driven vs polling).
