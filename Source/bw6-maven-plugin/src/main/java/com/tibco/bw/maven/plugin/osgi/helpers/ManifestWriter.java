package com.tibco.bw.maven.plugin.osgi.helpers;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;

import org.apache.maven.project.MavenProject;

import com.tibco.bw.maven.plugin.utils.Constants;

public class ManifestWriter {

    public static File updateManifest(MavenProject project , Manifest mf) throws IOException {
        
        File mfile = new File(project.getBuild().getDirectory(), "MANIFEST.MF");
        mfile.getParentFile().mkdirs();
        BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(mfile));
        try {
            mf.write(os);
        } finally {
        	if(os != null) {
        		os.close();	
        	}
        }
        return mfile;
    }
    
    
    public static void updateManifestVersion(Manifest mf, String qualifiedVersion)
    {
        Attributes attributes = mf.getMainAttributes();

        attributes.put(Name.MANIFEST_VERSION, qualifiedVersion);
        attributes.putValue(Constants.BUNDLE_VERSION, qualifiedVersion);

        //Updating provide capability for Shared Modules
//        if(BWProjectUtils.getModuleType(mf) == MODULE.SHAREDMODULE){
//        	String updatedProvide = ManifestParser.getUpdatedProvideCapabilities(mf, projectVersion);
//        	attributes.putValue(Constants.BUNDLE_PROVIDE_CAPABILITY, updatedProvide);
//        }

    }

}
