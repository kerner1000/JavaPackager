package io.github.fvarrui.javapackager.packagers;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.model.License;

import io.github.fvarrui.javapackager.maven.MavenContext;
import io.github.fvarrui.javapackager.model.Platform;
import io.github.fvarrui.javapackager.utils.CommandUtils;
import io.github.fvarrui.javapackager.utils.FileUtils;
import io.github.fvarrui.javapackager.utils.IconUtils;
import io.github.fvarrui.javapackager.utils.JavaUtils;
import io.github.fvarrui.javapackager.utils.Logger;
import io.github.fvarrui.javapackager.utils.VelocityUtils;

public abstract class Packager extends PackagerSettings {
	
	private static final String DEFAULT_ORGANIZATION_NAME = "ACME";

	// internal generic properties (setted in "createAppStructure")
	protected File appFolder;
	protected File assetsFolder;
	protected File executable;
	protected File jarFile;
	
	// internal packager specific properties (setted in "doCreateAppStructure")
	protected File executableDestinationFolder;
	protected File jarFileDestinationFolder;
	protected File jreDestinationFolder;
	protected File resourcesDestinationFolder;

	// ===============================================	
	
	public File getAppFolder() {
		return appFolder;
	}

	public File getAssetsFolder() {
		return assetsFolder;
	}

	public File getExecutable() {
		return executable;
	}

	public File getJarFile() {
		return jarFile;
	}

	// ===============================================
	// Functions
	// ===============================================	

	// create runnable JAR function
	
	protected Function<Packager, File> createRunnableJarFunction;
	
	public void setCreateRunnableJar(Function<Packager, File> createRunnableJarFunction) {
		this.createRunnableJarFunction = createRunnableJarFunction;
	}
	
	public File createRunnableJar() {
		return createRunnableJarFunction.apply(this);
	}

	// ===============================================
	
	public Packager() {
		super();
		Logger.info("Using packager " + this.getClass().getName());
	}
	
	private void init() throws Exception {
		
		Logger.infoIndent("Initializing packager ...");
		
		// sets assetsDir for velocity to locate custom velocity templates
		VelocityUtils.setAssetsDir(assetsDir);

		// using name as displayName, if it's not specified
		displayName = defaultIfBlank(displayName, name);
		
		// using displayName as description, if it's not specified
		description = defaultIfBlank(description, displayName);
		
		// using "ACME" as organizationName, if it's not specified
		organizationName = defaultIfBlank(organizationName, DEFAULT_ORGANIZATION_NAME);

		// using empty string as organizationUrl, if it's not specified
		organizationUrl = defaultIfBlank(organizationUrl, "");

		// determines target platform if not specified 
		if (platform == null || platform == Platform.auto) {
			platform = Platform.getCurrentPlatform();
		}
		
		// sets jdkPath by default if not specified
		if (jdkPath == null) {
			jdkPath = new File(System.getProperty("java.home"));
		}
		if (!jdkPath.exists()) {
			throw new Exception("JDK path doesn't exist: " + jdkPath);
		}
		
		// check if name is valid as filename
		try {
			Paths.get(name);
			if (name.contains("/")) throw new InvalidPathException(name, "Illegal char </>");
			if (name.contains("\\")) throw new InvalidPathException(name, "Illegal char <\\>");
		} catch (InvalidPathException e) {
			throw new Exception("Invalid name specified: " + name, e);
		}
		
		doInit();
		
		// removes not necessary platform specific configs 
		switch (platform) {
		case linux: macConfig = null; winConfig = null; break;
		case mac: winConfig = null; linuxConfig = null; break;
		case windows: linuxConfig = null; macConfig = null; break;
		default:
		}
		
		Logger.info("Effective packager configuration " + this);		
				
		Logger.infoUnindent("Packager initialized!");

	}

	public void resolveResources() throws Exception {
		
		Logger.infoIndent("Resolving resources ...");
		
		// locates license file
		licenseFile = resolveLicense(licenseFile, MavenContext.getEnv().getMavenProject().getLicenses());
		
		// locates icon file
		iconFile = resolveIcon(iconFile, name, assetsFolder);
		
		// adds to additional resources
		if (additionalResources != null) {
			if (licenseFile != null) additionalResources.add(licenseFile);
			additionalResources.add(iconFile);
			Logger.info("Effective additional resources " + additionalResources);			
		}		
		
		Logger.infoUnindent("Resources resolved!");
		
	}
	
	protected String getLicenseName() {
		List<License> licenses = MavenContext.getEnv().getMavenProject().getLicenses();
		return licenses != null && !licenses.isEmpty() && licenses.get(0) != null ? licenses.get(0).getName() : "";
	}

	/**
	 * Copies all dependencies to app folder
	 * 
	 * @param libsFolder folder containing all dependencies
	 * @throws Exception Process failed
	 */
	protected void copyAllDependencies(File libsFolder) throws Exception {
		if (!copyDependencies) return;

		Logger.infoIndent("Copying all dependencies to " + libsFolder.getName() + " folder ...");		
		
		// invokes plugin to copy dependecies to app libs folder
		executeMojo(
				plugin(
						groupId("org.apache.maven.plugins"), 
						artifactId("maven-dependency-plugin"), 
						version("3.1.1")
				),
				goal("copy-dependencies"),
				configuration(
						element("outputDirectory", libsFolder.getAbsolutePath())
				),
				MavenContext.getEnv());

		Logger.infoUnindent("All dependencies copied!");		
		
	}
	
	
	/**
	 * Copy a list of resources to a folder
	 * 
	 * @param resources   List of files and folders to be copied
	 * @param destination Destination folder. All specified resources will be copied
	 *                    here
	 */
	protected void copyAdditionalResources(List<File> resources, File destination) {

		Logger.infoIndent("Copying additional resources");
		
		resources.stream().forEach(r -> {
			if (!r.exists()) {
				Logger.warn("Additional resource " + r + " doesn't exist");
				return;
			}
			try {
				if (r.isDirectory()) {
					FileUtils.copyFolderToFolder(r, destination);
				} else if (r.isFile()) {
					FileUtils.copyFileToFolder(r, destination);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		Logger.infoUnindent("All additional resources copied!");
		
	}

	/**
	 * Bundle a Java Runtime Enrironment with the app.
	 * @param destinationFolder Destination folder
	 * @param jarFile Runnable jar file
	 * @param libsFolder Libs folder
	 * @param specificJreFolder Specific JRE folder to be used
	 * @param customizedJre Creates a reduced JRE
	 * @param defaultModules Default modules
	 * @param additionalModules Additional modules
	 * @param platform Target platform
	 * @throws Exception Process failed
	 */
	protected void bundleJre(File destinationFolder, File jarFile, File libsFolder, File specificJreFolder, boolean customizedJre, List<String> defaultModules, List<String> additionalModules, Platform platform) throws Exception {
		if (!bundleJre) {
			Logger.warn("Bundling JRE disabled by property 'bundleJre'!\n");
			return;
		}
		
		File currentJdk = new File(System.getProperty("java.home"));
		
		Logger.infoIndent("Bundling JRE ... with " + currentJdk.getAbsolutePath());
		
		if (specificJreFolder != null) {
			
			Logger.info("Embedding JRE from " + specificJreFolder);
			
			if (!specificJreFolder.exists()) {
				throw new Exception("JRE path specified does not exist: " + specificJreFolder.getAbsolutePath());
			} else if (!specificJreFolder.isDirectory()) {
				throw new Exception("JRE path specified is not a folder: " + specificJreFolder.getAbsolutePath());
			}
			
			// removes old jre folder from bundle
			if (destinationFolder.exists()) FileUtils.removeFolder(destinationFolder);

			// copies JRE folder to bundle
			FileUtils.copyFolderContentToFolder(specificJreFolder, destinationFolder);

			// sets execution permissions on executables in jre
			File binFolder = new File(destinationFolder, "bin");
			if (!binFolder.exists()) {
				throw new Exception("Could not embed JRE from " + specificJreFolder.getAbsolutePath() + ": " + binFolder.getAbsolutePath() + " doesn't exist");
			}
			Arrays.asList(binFolder.listFiles()).forEach(f -> f.setExecutable(true, false));

		} else if (JavaUtils.getJavaMajorVersion() <= 8) {
			
			throw new Exception("Could not create a customized JRE due to JDK version is " + SystemUtils.JAVA_VERSION + ". Must use jrePath property to specify JRE location to be embedded");
			
		} else if (!platform.isCurrentPlatform() && jdkPath.equals(currentJdk)) {
			
			Logger.warn("Cannot create a customized JRE ... target platform (" + platform + ") is different than execution platform (" + Platform.getCurrentPlatform() + "). Use jdkPath property.");
			
			bundleJre = false;
			
		} else {

			String modules = getRequiredModules(libsFolder, customizedJre, jarFile, defaultModules, additionalModules);

			Logger.info("Creating JRE with next modules included: " + modules);

			File modulesDir = new File(jdkPath, "jmods");
			if (!modulesDir.exists()) {
				throw new Exception("jmods folder doesn't exist: " + modulesDir);
			}
			
			Logger.info("Using " + modulesDir + " modules directory");
	
			File jlink = new File(currentJdk, "/bin/jlink");
	
			if (destinationFolder.exists()) FileUtils.removeFolder(destinationFolder);
			
			// generates customized jre using modules
			CommandUtils.execute(jlink.getAbsolutePath(), "--module-path", modulesDir, "--add-modules", modules, "--output", destinationFolder, "--no-header-files", "--no-man-pages", "--strip-debug", "--compress=2");
	
			// sets execution permissions on executables in jre
			File binFolder = new File(destinationFolder, "bin");
			Arrays.asList(binFolder.listFiles()).forEach(f -> f.setExecutable(true, false));

		}
		
		// removes jre/legal folder
		File legalFolder = new File(destinationFolder, "legal");
		if (legalFolder.exists()) {
			FileUtils.removeFolder(legalFolder);
		}
	
		if (bundleJre) {
			Logger.infoUnindent("JRE bundled in " + destinationFolder.getAbsolutePath() + "!");
		} else {
			Logger.infoUnindent("JRE bundling skipped!");
		}
		
	}

//	/**
//	 * Creates a runnable jar file from sources
//	 * @param name Name
//	 * @param version Version
//	 * @param mainClass Main class 
//	 * @param outputDirectory Output directory
//	 * @return Generated JAR file
//	 * @throws Exception Process failed
//	 */
//	protected File createRunnableJar(String name, String version, String mainClass, File outputDirectory) throws Exception {
//		Logger.infoIndent("Creating runnable JAR...");
//		
//		String classifier = "runnable";
//
//		File jarFile = new File(outputDirectory, name + "-" + version + "-" + classifier + ".jar");
//
//		executeMojo(
//				plugin(
//						groupId("org.apache.maven.plugins"),
//						artifactId("maven-jar-plugin"), 
//						version("3.1.1")
//				),
//				goal("jar"),
//				configuration(
//						element("classifier", classifier),
//						element("archive", 
//								element("manifest", 
//										element("addClasspath", "true"),
//										element("classpathPrefix", "libs/"),
//										element("mainClass", mainClass)
//								)
//						),
//						element("outputDirectory", jarFile.getParentFile().getAbsolutePath()),
//						element("finalName", name + "-" + version)
//				),
//				env);
//		
//		Logger.infoUnindent("Runnable jar created in " + jarFile.getAbsolutePath() + "!");
//		
//		return jarFile;
//	}
	
	/**
	 * Uses jdeps command tool to determine which modules all used jar files depend on
	 * 
	 * @param libsFolder folder containing all needed libraries
	 * @param customizedJre if true generates a customized JRE, including only identified or specified modules. Otherwise, all modules will be included.
	 * @param jarFile Runnable jar file reference
	 * @param defaultModules Additional files and folders to include in the bundled app.
	 * @param additionalModules Defines modules to customize the bundled JRE. Don't use jdeps to get module dependencies.
	 * @return string containing a comma separated list with all needed modules
	 * @throws Exception Process failed
	 */
	protected String getRequiredModules(File libsFolder, boolean customizedJre, File jarFile, List<String> defaultModules, List<String> additionalModules) throws Exception {
		
		Logger.infoIndent("Getting required modules ... ");
		
		File jdeps = new File(System.getProperty("java.home"), "/bin/jdeps");

		File jarLibs = null;
		if (libsFolder.exists()) 
			jarLibs = new File(libsFolder, "*.jar");
		else
			Logger.warn("No dependencies found!");
		
		List<String> modulesList;
		
		if (customizedJre && defaultModules != null && !defaultModules.isEmpty()) {
			
			modulesList = 
				defaultModules
					.stream()
					.map(module -> module.trim())
					.collect(Collectors.toList());
		
		} else if (customizedJre && JavaUtils.getJavaMajorVersion() >= 13) { 
			
			String modules = 
				CommandUtils.execute(
					jdeps.getAbsolutePath(), 
					"-q",
					"--multi-release", JavaUtils.getJavaMajorVersion(),
					"--ignore-missing-deps", 
					"--print-module-deps", 
					jarLibs,
					jarFile
				);
			
			modulesList = 
				Arrays.asList(modules.split(","))
					.stream()
					.map(module -> module.trim())
					.filter(module -> !module.isEmpty())
					.collect(Collectors.toList());
			
		} else if (customizedJre && JavaUtils.getJavaMajorVersion() >= 9) { 
		
			String modules = 
				CommandUtils.execute(
					jdeps.getAbsolutePath(), 
					"-q",
					"--multi-release", JavaUtils.getJavaMajorVersion(),
					"--list-deps", 
					jarLibs,
					jarFile
				);

			modulesList = 
				Arrays.asList(modules.split("\n"))
					.stream()
					.map(module -> module.trim())
					.map(module -> (module.contains("/") ? module.split("/")[0] : module))
					.filter(module -> !module.isEmpty())
					.filter(module -> !module.startsWith("JDK removed internal"))
					.distinct()
					.collect(Collectors.toList());

		} else {
			
			modulesList = Arrays.asList("ALL-MODULE-PATH");
			
		}
				
		modulesList.addAll(additionalModules);
		
		if (modulesList.isEmpty()) {
			Logger.warn("It was not possible to determine the necessary modules. All modules will be included");
			modulesList.add("ALL-MODULE-PATH");
		}
		
		Logger.infoUnindent("Required modules found: " + modulesList);
		
		return StringUtils.join(modulesList, ",");
	}

	/**
	 * Locates license file
	 * @param licenseFile Specified license file
	 * @param licenses Licenses list from POM
	 * @return Resolved license file
	 */
	protected File resolveLicense(File licenseFile, List<License> licenses) {
		
		// if default license file doesn't exist and there's a license specified in
		// pom.xml file, gets this last one
		if (licenseFile != null && !licenseFile.exists()) {
			Logger.warn("Specified license file doesn't exist: " + licenseFile.getAbsolutePath());
			licenseFile = null;
		}
		// if license not specified, gets from pom
		if (licenseFile == null && !licenses.isEmpty()) {
			
			String urlStr = null; 
			try {
				urlStr = licenses.get(0).getUrl(); 
				URL licenseUrl = new URL(urlStr);
				licenseFile = new File(assetsFolder, "LICENSE");
				FileUtils.downloadFromUrl(licenseUrl, licenseFile);
			} catch (MalformedURLException e) {
				Logger.error("Invalid license URL specified: " + urlStr);
				licenseFile = null;
			} catch (IOException e) {
				Logger.error("Cannot download license from " + urlStr);
				licenseFile = null;
			}
			
		}
		// if license is still null, looks for LICENSE file
		if (licenseFile == null || !licenseFile.exists()) {
			licenseFile = new File(MavenContext.getEnv().getMavenProject().getBasedir(), "LICENSE");
			if (!licenseFile.exists()) licenseFile = null;
		}
		
		if (licenseFile != null) {
			Logger.info("License file found: " + licenseFile.getAbsolutePath());
		} else {
			Logger.warn("No license file specified");
		}
		
		return licenseFile;
	}

	/**
	 * Locates assets or default icon file if the specified one doesn't exist or isn't specified
	 * @param iconFile Specified icon file
	 * @param name Name
	 * @param assetsFolder Assets folder
	 * @return Resolved icon file
	 * @throws Exception Process failed
	 */
	protected File resolveIcon(File iconFile, String name, File assetsFolder) throws Exception {
		
		String iconExtension = IconUtils.getIconFileExtensionByPlatform(platform);
		
		if (iconFile == null) {
			iconFile = new File(assetsDir, platform + "/" + name + iconExtension);
		}
		
		if (!iconFile.exists()) {
			iconFile = new File(assetsFolder, iconFile.getName());
			FileUtils.copyResourceToFile("/" + platform + "/default-icon" + iconExtension, iconFile);
		}
		
		Logger.info("Icon file resolved: " + iconFile.getAbsolutePath());
		
		return iconFile;
	}
	
	
	/**
	 * Bundling app folder in tarball and/or zipball 
	 * @throws Exception Process failed
	 */
	public void createBundles() throws Exception {
		if (!createTarball && !createZipball) return;

		Logger.infoIndent("Bundling app in tarball/zipball ...");
		
		// generate assembly.xml file 
		File assemblyFile = new File(assetsFolder, "assembly.xml");
		VelocityUtils.render("assembly.xml.vtl", assemblyFile, this);
		
		// invokes plugin to assemble zipball and/or tarball
		executeMojo(
				plugin(
						groupId("org.apache.maven.plugins"), 
						artifactId("maven-assembly-plugin"), 
						version("3.1.1")
				),
				goal("single"),
				configuration(
						element("descriptors", element("descriptor", assemblyFile.getAbsolutePath())),
						element("finalName", name + "-" + version + "-" + platform),
						element("appendAssemblyId", "false")
				),
				MavenContext.getEnv());
		
		Logger.infoUnindent("Bundles created!");
		
	}
	
	private void createAppStructure() throws Exception {
		
		Logger.infoIndent("Creating app structure ...");
		
		// creates output directory if it doesn't exist
		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs();
		}

		// creates app destination folder
		appFolder = new File(outputDirectory, name);
		if (appFolder.exists()) {
			FileUtils.removeFolder(appFolder);
			Logger.info("Old app folder removed " + appFolder.getAbsolutePath());
		} 
		appFolder = FileUtils.mkdir(outputDirectory, name);
		Logger.info("App folder created: " + appFolder.getAbsolutePath());

		// creates folder for intermmediate assets 
		assetsFolder = FileUtils.mkdir(outputDirectory, "assets");
		Logger.info("Assets folder created: " + assetsFolder.getAbsolutePath());

		// create the rest of the structure
		doCreateAppStructure();

		Logger.infoUnindent("App structure created!");
		
	}

	public File createApp() throws Exception {
		
		Logger.infoIndent("Creating app ...");

		init();

		// creates app folders structure
		createAppStructure();
		
		// resolve resources
		resolveResources();

		// copies additional resources
		copyAdditionalResources(additionalResources, resourcesDestinationFolder);
				
		// creates a runnable jar file
        if (runnableJar != null && runnableJar.exists()) {
        	Logger.info("Using runnable JAR: " + runnableJar);
            jarFile = runnableJar;
        } else {
            jarFile = createRunnableJar();
        }
        
		// copies all dependencies to Java folder
		File libsFolder = new File(jarFileDestinationFolder, "libs");
		copyAllDependencies(libsFolder);

		// checks if JRE should be embedded
		bundleJre(jreDestinationFolder, jarFile, libsFolder, jrePath, customizedJre, modules, additionalModules, platform);
        
        File appFile = doCreateApp();

		Logger.infoUnindent("App created in " + appFolder.getAbsolutePath() + "!");
		        
		return appFile;
	}

	public List<File> generateInstallers() throws Exception {
		List<File> installers = new ArrayList<>();
		
		if (!generateInstaller) {
			Logger.warn("Installer generation is disabled by 'generateInstaller' property!");
			return installers;
		}
		if (!platform.isCurrentPlatform()) {
			Logger.warn("Installers cannot be generated due to the target platform (" + platform + ") is different from the execution platform (" + Platform.getCurrentPlatform() + ")!");
			return installers;
		}
		
		Logger.infoIndent("Generating installers ...");

		init();
		
		// creates folder for intermmediate assets if it doesn't exist  
		assetsFolder = FileUtils.mkdir(outputDirectory, "assets");
		
		doGenerateInstallers(installers);
		
		Logger.infoUnindent("Installers generated! " + installers);
		
		return installers;		
	}

	@Override
	public String toString() {
		return "[appFolder=" + appFolder + ", assetsFolder=" + assetsFolder + ", executable=" + executable
				+ ", jarFile=" + jarFile + ", executableDestinationFolder=" + executableDestinationFolder
				+ ", jarFileDestinationFolder=" + jarFileDestinationFolder + ", jreDestinationFolder="
				+ jreDestinationFolder + ", resourcesDestinationFolder=" + resourcesDestinationFolder
				+ ", outputDirectory=" + outputDirectory + ", licenseFile=" + licenseFile + ", iconFile=" + iconFile
				+ ", generateInstaller=" + generateInstaller + ", mainClass=" + mainClass + ", name=" + name
				+ ", displayName=" + displayName + ", version=" + version + ", description=" + description + ", url="
				+ url + ", administratorRequired=" + administratorRequired + ", organizationName=" + organizationName
				+ ", organizationUrl=" + organizationUrl + ", organizationEmail=" + organizationEmail + ", bundleJre="
				+ bundleJre + ", customizedJre=" + customizedJre + ", jrePath=" + jrePath + ", additionalResources="
				+ additionalResources + ", modules=" + modules + ", additionalModules=" + additionalModules
				+ ", platform=" + platform + ", envPath=" + envPath + ", vmArgs=" + vmArgs + ", runnableJar="
				+ runnableJar + ", copyDependencies=" + copyDependencies + ", jreDirectoryName=" + jreDirectoryName
				+ ", winConfig=" + winConfig + ", linuxConfig=" + linuxConfig + ", macConfig=" + macConfig
				+ ", createTarball=" + createTarball + ", createZipball=" + createZipball + ", extra=" + extra
				+ ", useResourcesAsWorkingDir=" + useResourcesAsWorkingDir + ", assetsDir=" + assetsDir + "]";
	}

	protected abstract void doCreateAppStructure() throws Exception; 

	public abstract File doCreateApp() throws Exception;
	
	public abstract void doGenerateInstallers(List<File> installers) throws Exception;
	
	public abstract void doInit() throws Exception;
	
}
