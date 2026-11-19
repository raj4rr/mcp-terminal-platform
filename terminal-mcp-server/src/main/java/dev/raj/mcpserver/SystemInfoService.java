package dev.raj.mcpserver;

import java.nio.file.Path;

public class SystemInfoService {
    public String getSystemInfo(Path root) {
        return "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
            + "\nJava: " + System.getProperty("java.version")
            + "\nWorkspace: " + root;
    }
}
