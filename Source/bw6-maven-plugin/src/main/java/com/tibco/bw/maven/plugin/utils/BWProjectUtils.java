package com.tibco.bw.maven.plugin.utils;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

import com.tibco.bw.maven.plugin.osgi.helpers.VersionParser;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import com.tibco.bw.maven.plugin.osgi.helpers.ManifestParser;

public class BWProjectUtils {


    public static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    public enum OS {
        WINDOWS, UNIX
    }

    public enum MODULE {
        APPMODULE, SHAREDMODULE, APPLICATION, CUSTOMXPATH
    }

    public static String getModuleVersion(File jarFile) throws Exception {
        JarInputStream jarStream = new JarInputStream(new FileInputStream(jarFile));
        Manifest moduleManifest = jarStream.getManifest();
        jarStream.close();
        return moduleManifest.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
    }


    public static MODULE getModuleType(File jarFile) {

        Manifest manifest = null;
        try {
            JarInputStream jarStream = new JarInputStream(new FileInputStream(jarFile));
            manifest = jarStream.getManifest();
            jarStream.close();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return getModuleType(manifest);
    }

    public static MODULE getModuleType(Manifest manifest) {
        if (manifest != null) {
            Attributes attributes = manifest.getMainAttributes();

            if (attributes.getValue("TIBCO-BW-ApplicationModule") != null) {
                return MODULE.APPMODULE;
            } else if (attributes.getValue("TIBCO-BW-SharedModule") != null) {
                return MODULE.SHAREDMODULE;
            } else if (attributes.getValue("TIBCO-BW-Application") != null) {
                return MODULE.APPLICATION;
            }
        }

        return null;
    }


    public static String getAdminExecutable() {
        String os = System.getProperty("os.name");
        if (os.indexOf("Windows") != -1) {
            return "/bwadmin.exe";
        }
        return "/bwadmin";
    }

    public static OS getOS() {
        String os = System.getProperty("os.name");
        if (os.indexOf("Windows") != -1) {
            return OS.WINDOWS;
        }
        return OS.UNIX;
    }

    public static File getBWAdminHome(String tibcoHome, String bwVersion) throws Exception {
        File bwAdminHome = new File(new File(tibcoHome), "bw/" + bwVersion + "/bin/");
        if (bwAdminHome.exists()) {
            return bwAdminHome;
        }
        throw new MojoExecutionException("Failed to find Admin Home at location: " + bwAdminHome);
    }

    public static MavenProject getApplicationProject(MavenSession session) {

        List<MavenProject> projects = session.getAllProjects();
        for (MavenProject project : projects) {
            Manifest mf = ManifestParser.parseManifest(project.getBasedir());
            MODULE module = BWProjectUtils.getModuleType(mf);

            if (module == MODULE.APPLICATION) {
                return project;
            }
        }
        return null;
    }

    public static String computeQualifiedVersion(String qualifierReplacement, String projectVersion) {
        if (projectVersion.contains(SNAPSHOT_SUFFIX)) {
            if (projectVersion.contains("_")) {
                int i = projectVersion.indexOf("_");
                int i1 = projectVersion.lastIndexOf(SNAPSHOT_SUFFIX);
                return projectVersion.substring(0, i) + "." + projectVersion.substring(i+1, i1);
            } else {
                String qualifierReplacedVersion = projectVersion.replace(SNAPSHOT_SUFFIX, "." + VersionParser.QUALIFIER);
                return VersionParser.getcalculatedOSGiVersion(qualifierReplacedVersion, qualifierReplacement);
            }
        } else return projectVersion;
    }

}
