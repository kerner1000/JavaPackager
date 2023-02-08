package io.github.fvarrui.javapackager.packagers;

import io.github.fvarrui.javapackager.model.MacStartup;
import io.github.fvarrui.javapackager.model.Platform;
import io.github.fvarrui.javapackager.utils.*;
import io.github.javacodesign.Notarizer;
import io.github.javacodesign.Signer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.codehaus.plexus.util.cli.CommandLineException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Packager for MacOS
 */
public class MacPackager extends Packager {

	private File appFile;
	private File contentsFolder;
	private File resourcesFolder;
	private File javaFolder;
	private File macOSFolder;

	public File getAppFile() {
		return appFile;
	}

	@Override
	public void doInit() throws Exception {

		this.macConfig.setDefaults(this);

		// FIX useResourcesAsWorkingDir=false doesn't work fine on Mac OS (option disabled)
		if (!this.isUseResourcesAsWorkingDir()) {
			this.useResourcesAsWorkingDir = true;
			Logger.warn("'useResourcesAsWorkingDir' property disabled on Mac OS (useResourcesAsWorkingDir is always true)");
		}

	}

	@Override
	protected void doCreateAppStructure() throws Exception {

		// initializes the references to the app structure folders
		this.appFile = new File(appFolder, name + ".app");
		this.contentsFolder = new File(appFile, "Contents");
		this.resourcesFolder = new File(contentsFolder, "Resources");
		this.javaFolder = new File(resourcesFolder, this.macConfig.isRelocateJar() ? "Java" : "");
		this.macOSFolder = new File(contentsFolder, "MacOS");

		// makes dirs

		FileUtils.mkdir(this.appFile);
		Logger.info("App file folder created: " + appFile.getAbsolutePath());

		FileUtils.mkdir(this.contentsFolder);
		Logger.info("Contents folder created: " + contentsFolder.getAbsolutePath());

		FileUtils.mkdir(this.resourcesFolder);
		Logger.info("Resources folder created: " + resourcesFolder.getAbsolutePath());

		FileUtils.mkdir(this.javaFolder);
		Logger.info("Java folder created: " + javaFolder.getAbsolutePath());

		FileUtils.mkdir(this.macOSFolder);
		Logger.info("MacOS folder created: " + macOSFolder.getAbsolutePath());

		// sets common folders
		this.executableDestinationFolder = macOSFolder;
		this.jarFileDestinationFolder = javaFolder;
		this.jreDestinationFolder = new File(contentsFolder, "PlugIns/" + jreDirectoryName + "/Contents/Home");
		this.resourcesDestinationFolder = resourcesFolder;

	}

	/**
	 * Creates a native MacOS app bundle
	 */
	@Override
	public File doCreateApp() throws Exception {

		// copies jarfile to Java folder
		FileUtils.copyFileToFolder(jarFile, javaFolder);

		processStartupScript();

		processClasspath();

		processInfoPlistFile();

		processProvisionProfileFile();

		codesign();

		notarize();

		return appFile;
	}

	private void processStartupScript() throws Exception {
		
		if (this.administratorRequired) {

			// We need a helper script ("startup") in this case,
			// which invokes the launcher script/ executable with administrator rights.
			// TODO: admin script depends on launcher file name 'universalJavaApplicationStub'

			// sets startup file
			this.executable = new File(macOSFolder, "startup");

			// creates startup file to boot java app
			VelocityUtils.render("mac/startup.vtl", executable, this);
			
		} else {

			File launcher = macConfig.getCustomLauncher();
			if (launcher != null && launcher.canRead() && launcher.isFile()){
				FileUtils.copyFileToFolder(launcher, macOSFolder);
				this.executable = new File(macOSFolder, launcher.getName());
			} else {
				this.executable = preparePrecompiledStartupStub();
			}
		}
		
		executable.setExecutable(true, false);
		Logger.info("Startup script file created in " + executable.getAbsolutePath());
	}

	private void processClasspath() {
		// TODO: Why are we doing this here? I do not see any usage of 'classpath' or 'classpaths' here.
		classpath = (this.macConfig.isRelocateJar() ? "Java/" : "") + this.jarFile.getName() + (classpath != null ? ":" + classpath : "");
		classpaths = Arrays.asList(classpath.split("[:;]"));
		if (!isUseResourcesAsWorkingDir()) {
			classpaths = classpaths
					.stream()
					.map(cp -> new File(cp).isAbsolute() ? cp : "$ResourcesFolder/" + cp)
					.collect(Collectors.toList());
		}
		classpath = StringUtils.join(classpaths, ":");
	}

	/**
	 * Creates and writes the Info.plist file if no custom file is specified.
	 * @throws Exception if anything goes wrong
	 */
	private void processInfoPlistFile() throws Exception {
		File infoPlistFile = new File(contentsFolder, "Info.plist");
		if(macConfig.getCustomInfoPlist() != null && macConfig.getCustomInfoPlist().isFile() && macConfig.getCustomInfoPlist().canRead()){
			FileUtils.copyFileToFile(macConfig.getCustomInfoPlist(), infoPlistFile);
		} else {
			VelocityUtils.render("mac/Info.plist.vtl", infoPlistFile, this);
			XMLUtils.prettify(infoPlistFile);
		}
		Logger.info("Info.plist file created in " + infoPlistFile.getAbsolutePath());
	}

	private void codesign() throws Exception {
		if (!Platform.mac.isCurrentPlatform()) {
			Logger.warn("Generated app could not be signed due to current platform is " + Platform.getCurrentPlatform());
		} else if (!getMacConfig().isCodesignApp()) {
			Logger.warn("App codesigning disabled");
		} else {
			codesign(this.macConfig.getDeveloperId(), this.macConfig.getEntitlements(), this.appFile, executable);
		}
	}

	private void processProvisionProfileFile() throws Exception {
		if (macConfig.getProvisionProfile() != null && macConfig.getProvisionProfile().isFile() && macConfig.getProvisionProfile().canRead()) {
			// file name must be 'embedded.provisionprofile'
			File provisionProfile = new File(contentsFolder, "embedded.provisionprofile");
			FileUtils.copyFileToFile(macConfig.getProvisionProfile(), provisionProfile);
			Logger.info("Provision profile file created from " + "\n" +
					macConfig.getProvisionProfile() + " to \n" +
					provisionProfile.getAbsolutePath());
		}
	}

	private File preparePrecompiledStartupStub() throws Exception {
		// sets startup file
		File appStubFile = new File(macOSFolder, "universalJavaApplicationStub");
		String universalJavaApplicationStubResource = null;
		switch (macConfig.getMacStartup()) {
			case UNIVERSAL:	universalJavaApplicationStubResource = "universalJavaApplicationStub"; break;
			case X86_64:	universalJavaApplicationStubResource = "universalJavaApplicationStub.x86_64"; break;
			case ARM64: 	universalJavaApplicationStubResource = "universalJavaApplicationStub.arm64"; break;
			case SCRIPT: 	universalJavaApplicationStubResource = "universalJavaApplicationStub.sh"; break;
		}
		// unixStyleNewLinux=true if startup is a script (this will replace '\r\n' with '\n')
		FileUtils.copyResourceToFile("/mac/" + universalJavaApplicationStubResource, appStubFile, macConfig.getMacStartup() == MacStartup.SCRIPT);
		return appStubFile;
	}

	private void codesign(String developerId, File entitlements, File appFile, File executable) throws Exception {

		Signer signer;
		if(entitlements != null) {
			Logger.info("Using provided entitlements for both JVM and launcher " + entitlements);
			signer = new Signer(developerId, appFile.toPath(), executable.toPath(), entitlements.toPath(), entitlements.toPath());
		}
		else {
			Logger.info("Using default entitlements for JVM and launcher");
			signer = new Signer(developerId, appFile.toPath(), executable.toPath());
		}
		signer.sign();
	}
	private void notarize() throws IOException {
		if (!Platform.mac.isCurrentPlatform()) {
			Logger.warn("Generated app could not be notarized due to current platform is " + Platform.getCurrentPlatform());
		} else if (!getMacConfig().isCodesignApp()) {
			Logger.info("App notarization disabled");
		} else {
			String primaryBundleId = macConfig.getAppId();
			String apiKey = macConfig.getApiKey();
			String apiIssuer = macConfig.getApiIssuer();
			Notarizer notarizer = new Notarizer(primaryBundleId, apiKey, apiIssuer, this.appFile.toPath());
			boolean notarizationResult = notarizer.notarize();
			if(notarizationResult)
				Logger.info("Notarization success!");
			else
				Logger.warn("Notarization result not as expected. That does not mean it failed necessarily, maybe we just didn't wait long enough.");
		}
	}

}
