package io.jenkins.plugins.jfrog;

import org.apache.commons.lang3.SystemUtils;

import java.io.IOException;

/**
 * @author gail
 */
public class OsUtils {
    private static final String OS_ARCH = System.getProperty("os.arch").toLowerCase();

    public static String getOsDetails() throws IOException {
        // Windows
        if (SystemUtils.IS_OS_WINDOWS) {
            return "windows-amd64";
        }
        // Mac
        if (SystemUtils.IS_OS_MAC) {
            if (OS_ARCH.contains("arm64")) {
                return "mac-arm64";
            }
            return "mac-386";
        }
        // Unix
        switch (OS_ARCH) {
            case ("i386"):
            case ("i486"):
            case ("i586"):
            case ("i686"):
            case ("i786"):
            case ("x86"):
                return "linux-386";
            case ("amd64"):
            case ("x86_64"):
            case ("x64"):
                return "linux-amd64";
            case ("arm"):
            case ("armv7l"):
                return "linux-arm";
            case ("aarch64"):
                return "linux-arm64";
            case ("s390x"):
                return "linux-s390x";
            case ("ppc64"):
                return "linux-ppc64";
            case ("ppc64le"):
                return "linux-ppc64le";
        }
        throw new IOException("Unsupported operating system: " + OS_ARCH);
    }
}
