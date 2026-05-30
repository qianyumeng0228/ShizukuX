sed -i 's/ServiceManager.waitForService(name);/ServiceManager.class.getMethod("waitForService", String.class).invoke(null, name);/' server/src/main/java/rikka/shizuku/server/ShizukuService.java
