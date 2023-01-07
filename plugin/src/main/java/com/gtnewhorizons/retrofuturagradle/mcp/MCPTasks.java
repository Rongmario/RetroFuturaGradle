package com.gtnewhorizons.retrofuturagradle.mcp;

import com.google.common.collect.ImmutableMap;
import com.gtnewhorizons.retrofuturagradle.BuildConfig;
import com.gtnewhorizons.retrofuturagradle.Constants;
import com.gtnewhorizons.retrofuturagradle.MinecraftExtension;
import com.gtnewhorizons.retrofuturagradle.ObfuscationAttribute;
import com.gtnewhorizons.retrofuturagradle.minecraft.MinecraftTasks;
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask;
import com.gtnewhorizons.retrofuturagradle.util.Utilities;
import cpw.mods.fml.relauncher.Side;
import de.undercouch.gradle.tasks.download.Download;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmVersion;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.ConfigurationVariantDetails;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.artifacts.dependencies.DefaultDependencyArtifact;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.scala.ScalaCompile;
import org.gradle.jvm.tasks.Jar;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * Tasks reproducing the MCP/FML/Forge toolchain for deobfuscation
 */
public class MCPTasks {
    private static final String TASK_GROUP_INTERNAL = "Internal Modded Minecraft";
    private static final String TASK_GROUP_USER = "Modded Minecraft";
    public static final String RFG_DIR = "rfg";
    public static final String SOURCE_SET_PATCHED_MC = "patchedMc";
    public static final String SOURCE_SET_LAUNCHER = "mcLauncher";

    private final Project project;
    private final MinecraftExtension mcExt;
    private final MinecraftTasks mcTasks;

    private final Configuration mcpMappingDataConfiguration;
    private final Configuration forgeUserdevConfiguration;
    private final Configuration forgeUniversalConfiguration;

    private final File fernflowerLocation;
    private final TaskProvider<Download> taskDownloadFernflower;

    private final File mcpDataLocation;
    private final TaskProvider<Copy> taskExtractMcpData;
    private final File forgeUserdevLocation;
    private final TaskProvider<Copy> taskExtractForgeUserdev;
    private final File forgeSrgLocation;
    private final TaskProvider<GenSrgMappingsTask> taskGenerateForgeSrgMappings;
    private final File mergedVanillaJarLocation;
    private final TaskProvider<MergeSidedJarsTask> taskMergeVanillaSidedJars;
    /**
     * Merged C+S jar remapped to SRG names
     */
    private final File srgMergedJarLocation;

    private final TaskProvider<DeobfuscateTask> taskDeobfuscateMergedJarToSrg;
    private final ConfigurableFileCollection deobfuscationATs;

    private final TaskProvider<DecompileTask> taskDecompileSrgJar;
    private final TaskProvider<CleanupDecompiledJarTask> taskCleanupDecompSrgJar;
    private final File decompiledSrgLocation;

    private final TaskProvider<PatchSourcesTask> taskPatchDecompiledJar;
    private final File patchedSourcesLocation;

    private final TaskProvider<RemapSourceJarTask> taskRemapDecompiledJar;
    private final File remappedSourcesLocation;

    private final TaskProvider<Copy> taskDecompressDecompiledSources;
    private final File decompressedSourcesLocation;
    private final Configuration patchedConfiguration;
    private final SourceSet patchedMcSources;
    private final TaskProvider<JavaCompile> taskBuildPatchedMc;
    private final File packagedMcLocation;
    private final TaskProvider<Jar> taskPackagePatchedMc;
    private final File launcherSourcesLocation;
    private final TaskProvider<CreateLauncherFiles> taskCreateLauncherFiles;
    private final SourceSet launcherSources;
    private final File packagedMcLauncherLocation;
    private final TaskProvider<Jar> taskPackageMcLauncher;
    private final TaskProvider<RunMinecraftTask> taskRunClient;
    private final TaskProvider<RunMinecraftTask> taskRunServer;

    private final File binaryPatchedMcLocation;
    private final TaskProvider<BinaryPatchJarTask> taskInstallBinaryPatchedVersion;
    private final File srgBinaryPatchedMcLocation;
    private final TaskProvider<DeobfuscateTask> taskSrgifyBinaryPatchedVersion;
    private final TaskProvider<RunMinecraftTask> taskRunObfClient;
    private final TaskProvider<RunMinecraftTask> taskRunObfServer;
    private final Configuration obfRuntimeClasspathConfiguration;
    private final Configuration reobfJarConfiguration;
    private final TaskProvider<InjectTagsTask> taskInjectTags;
    private final SourceSet injectedSourceSet;
    private final File injectedSourcesLocation;

    public Provider<RegularFile> mcpFile(String path) {
        return project.getLayout()
                .file(taskExtractMcpData.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<RegularFile> userdevFile(String path) {
        return project.getLayout()
                .file(taskExtractForgeUserdev.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public Provider<Directory> userdevDir(String path) {
        return project.getLayout()
                .dir(taskExtractForgeUserdev.map(Copy::getDestinationDir).map(d -> new File(d, path)));
    }

    public MCPTasks(Project project, MinecraftExtension mcExt, MinecraftTasks mcTasks) {
        this.project = project;
        this.mcExt = mcExt;
        this.mcTasks = mcTasks;

        final ObjectFactory objectFactory = project.getObjects();

        project.afterEvaluate(p -> this.afterEvaluate());

        mcpMappingDataConfiguration = project.getConfigurations().create("mcpMappingData");
        mcpMappingDataConfiguration.setCanBeConsumed(false);
        forgeUserdevConfiguration = project.getConfigurations().create("forgeUserdev");
        forgeUserdevConfiguration.setCanBeConsumed(false);
        forgeUniversalConfiguration = project.getConfigurations().create("forgeUniversal");
        forgeUniversalConfiguration.setCanBeConsumed(false);
        deobfuscationATs = project.getObjects().fileCollection();

        final File fernflowerDownloadLocation = Utilities.getCacheDir(project, "mcp", "fernflower-fixed.zip");
        fernflowerLocation = Utilities.getCacheDir(project, "mcp", "fernflower.jar");
        taskDownloadFernflower = project.getTasks().register("downloadFernflower", Download.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.src(Constants.URL_FERNFLOWER);
            task.onlyIf(t -> !fernflowerLocation.exists());
            task.overwrite(false);
            task.onlyIfModified(true);
            task.useETag(true);
            task.dest(fernflowerDownloadLocation);
            task.doLast(_t -> {
                try (final FileInputStream fis = new FileInputStream(fernflowerDownloadLocation);
                        final ZipInputStream zis = new ZipInputStream(fis);
                        final FileOutputStream fos = new FileOutputStream(fernflowerLocation)) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.getName().toLowerCase(Locale.ROOT).endsWith("fernflower.jar")) {
                            IOUtils.copy(zis, fos);
                            break;
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            task.getOutputs().file(fernflowerLocation);
        });

        mcpDataLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "data");
        taskExtractMcpData = project.getTasks().register("extractMcpData", Copy.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(project.provider(() -> project.zipTree(getMcpMappingDataConfiguration()
                    .fileCollection(Specs.SATISFIES_ALL)
                    .getSingleFile())));
            task.into(mcpDataLocation);
        });

        forgeUserdevLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "userdev");
        taskExtractForgeUserdev = project.getTasks().register("extractForgeUserdev", Copy.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.from(project.provider(() -> project.zipTree(getForgeUserdevConfiguration()
                    .fileCollection(Specs.SATISFIES_ALL)
                    .getSingleFile())));
            task.into(forgeUserdevLocation);
        });

        forgeSrgLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "forge_srg");
        taskGenerateForgeSrgMappings = project.getTasks()
                .register("generateForgeSrgMappings", GenSrgMappingsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractMcpData, taskExtractForgeUserdev);
                    // inputs
                    task.getInputSrg().set(userdevFile("conf/packaged.srg"));
                    task.getInputExc().set(userdevFile("conf/packaged.exc"));
                    task.getFieldsCsv()
                            .set(mcExt.getUseForgeEmbeddedMappings()
                                    .flatMap(useForge -> useForge.booleanValue()
                                            ? userdevFile("conf/fields.csv")
                                            : mcpFile("fields.csv")));
                    task.getMethodsCsv()
                            .set(mcExt.getUseForgeEmbeddedMappings()
                                    .flatMap(useForge -> useForge.booleanValue()
                                            ? userdevFile("conf/methods.csv")
                                            : mcpFile("methods.csv")));
                    // outputs
                    task.getNotchToSrg().set(FileUtils.getFile(forgeSrgLocation, "notch-srg.srg"));
                    task.getNotchToMcp().set(FileUtils.getFile(forgeSrgLocation, "notch-mcp.srg"));
                    task.getSrgToMcp().set(FileUtils.getFile(forgeSrgLocation, "srg-mcp.srg"));
                    task.getMcpToSrg().set(FileUtils.getFile(forgeSrgLocation, "mcp-srg.srg"));
                    task.getMcpToNotch().set(FileUtils.getFile(forgeSrgLocation, "mcp-notch.srg"));
                    task.getSrgExc().set(FileUtils.getFile(forgeSrgLocation, "srg.exc"));
                    task.getMcpExc().set(FileUtils.getFile(forgeSrgLocation, "mcp.exc"));
                });

        mergedVanillaJarLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "vanilla_merged_minecraft.jar");
        taskMergeVanillaSidedJars = project.getTasks()
                .register("mergeVanillaSidedJars", MergeSidedJarsTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractForgeUserdev, mcTasks.getTaskDownloadVanillaJars());
                    task.onlyIf(t -> !mergedVanillaJarLocation.exists());
                    task.getClientJar().set(mcTasks.getVanillaClientLocation());
                    task.getServerJar().set(mcTasks.getVanillaServerLocation());
                    task.getMergedJar().set(mergedVanillaJarLocation);
                    task.getMergeConfigFile().set(FileUtils.getFile(forgeUserdevLocation, "conf", "mcp_merge.cfg"));
                    task.getMcVersion().set(mcExt.getMcVersion());
                });

        srgMergedJarLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft.jar");
        taskDeobfuscateMergedJarToSrg = project.getTasks()
                .register("deobfuscateMergedJarToSrg", DeobfuscateTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskMergeVanillaSidedJars, taskGenerateForgeSrgMappings);
                    task.getSrgFile().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg));
                    task.getExceptorJson().set(userdevFile("conf/exceptor.json"));
                    task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(MergeSidedJarsTask::getMergedJar));
                    task.getOutputJar().set(srgMergedJarLocation);
                    // No fields or methods CSV - passing them in causes ATs to not successfully apply
                    task.getIsApplyingMarkers().set(true);
                    // Configured in afterEvaluate()
                    task.getAccessTransformerFiles().setFrom(deobfuscationATs);
                });

        decompiledSrgLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft-sources.jar");
        final File rawDecompiledSrgLocation =
                FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_merged_minecraft-sources-rawff.jar");
        taskDecompileSrgJar = project.getTasks().register("decompileSrgJar", DecompileTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskDeobfuscateMergedJarToSrg, taskDownloadFernflower);
            task.getInputJar().set(taskDeobfuscateMergedJarToSrg.flatMap(DeobfuscateTask::getOutputJar));
            task.getOutputJar().set(rawDecompiledSrgLocation);
            task.getCacheDir().set(Utilities.getCacheDir(project, "fernflower-cache"));
            task.getFernflower().set(fernflowerLocation);
        });
        taskCleanupDecompSrgJar = project.getTasks()
                .register("cleanupDecompSrgJar", CleanupDecompiledJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDecompileSrgJar, taskExtractForgeUserdev);
                    task.getInputJar().set(taskDecompileSrgJar.flatMap(DecompileTask::getOutputJar));
                    task.getOutputJar().set(decompiledSrgLocation);
                    task.getPatches().set(userdevDir("conf/minecraft_ff"));
                    task.getAstyleConfig().set(userdevFile("conf/astyle.cfg"));
                });

        patchedSourcesLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_patched_minecraft-sources.jar");
        taskPatchDecompiledJar = project.getTasks().register("patchDecompiledJar", PatchSourcesTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCleanupDecompSrgJar);
            task.getInputJar().set(taskCleanupDecompSrgJar.flatMap(CleanupDecompiledJarTask::getOutputJar));
            task.getOutputJar().set(patchedSourcesLocation);
            task.getMaxFuzziness().set(1);
        });

        remappedSourcesLocation =
                FileUtils.getFile(project.getBuildDir(), RFG_DIR, "mcp_patched_minecraft-sources.jar");
        taskRemapDecompiledJar = project.getTasks().register("remapDecompiledJar", RemapSourceJarTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskPatchDecompiledJar, taskExtractForgeUserdev, taskExtractMcpData);
            task.getInputJar().set(taskPatchDecompiledJar.flatMap(PatchSourcesTask::getOutputJar));
            task.getOutputJar().set(remappedSourcesLocation);
            task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
            task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
            task.getParamCsv().set(mcpFile("params.csv"));
            task.getAddJavadocs().set(true);
        });

        decompressedSourcesLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "minecraft-src");
        taskDecompressDecompiledSources = project.getTasks()
                .register("decompressDecompiledSources", Copy.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskRemapDecompiledJar);
                    task.from(
                            project.zipTree(taskRemapDecompiledJar.flatMap(RemapSourceJarTask::getOutputJar)),
                            subset -> {
                                subset.include("**/*.java");
                            });
                    task.from(
                            project.zipTree(taskRemapDecompiledJar.flatMap(RemapSourceJarTask::getOutputJar)),
                            subset -> {
                                subset.exclude("**/*.java");
                            });
                    task.eachFile(fcd -> {
                        fcd.setRelativePath(
                                fcd.getRelativePath().prepend(fcd.getName().endsWith(".java") ? "java" : "resources"));
                    });
                    task.into(decompressedSourcesLocation);
                });

        this.patchedConfiguration = project.getConfigurations().create("patchedMinecraft");
        this.patchedConfiguration.extendsFrom(mcTasks.getVanillaMcConfiguration());
        this.patchedConfiguration.setDescription("Dependencies needed to run modded minecraft");
        this.patchedConfiguration.setCanBeConsumed(false);
        // Workaround https://github.com/gradle/gradle/issues/10861 to avoid publishing these dependencies
        for (String configName : new String[] {
            JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
            JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME,
            JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME,
            JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME
        }) {
            project.getConfigurations().getByName(configName).extendsFrom(this.patchedConfiguration);
        }

        final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        final JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);

        patchedMcSources = sourceSets.create(SOURCE_SET_PATCHED_MC, sourceSet -> {
            sourceSet.setCompileClasspath(patchedConfiguration);
            sourceSet.setRuntimeClasspath(patchedConfiguration);
            sourceSet.java(java -> java.setSrcDirs(project.files(new File(decompressedSourcesLocation, "java"))
                    .builtBy(taskDecompressDecompiledSources)));
            sourceSet.resources(
                    java -> java.setSrcDirs(project.files(new File(decompressedSourcesLocation, "resources"))
                            .builtBy(taskDecompressDecompiledSources)));
        });
        javaExt.getSourceSets().add(patchedMcSources);

        taskBuildPatchedMc = project.getTasks()
                .named(patchedMcSources.getCompileJavaTaskName(), JavaCompile.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskDecompressDecompiledSources);
                    configureMcJavaCompilation(task);
                });
        project.getTasks()
                .named(patchedMcSources.getProcessResourcesTaskName())
                .configure(task -> task.dependsOn(taskDecompressDecompiledSources));

        packagedMcLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "recompiled_minecraft.jar");
        taskPackagePatchedMc = project.getTasks().register("packagePatchedMc", Jar.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskBuildPatchedMc, taskDecompressDecompiledSources, patchedMcSources.getClassesTaskName());
            task.getArchiveVersion().set(mcExt.getMcVersion());
            task.getArchiveBaseName().set(StringUtils.removeEnd(packagedMcLocation.getName(), ".jar"));
            task.getDestinationDirectory().set(packagedMcLocation.getParentFile());
            task.from(patchedMcSources.getOutput());
        });

        launcherSourcesLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "launcher-src");
        taskCreateLauncherFiles = project.getTasks()
                .register("createMcLauncherFiles", CreateLauncherFiles.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractMcpData, taskExtractForgeUserdev, mcTasks.getTaskExtractNatives());
                    task.getOutputDir().set(launcherSourcesLocation);
                    final ProviderFactory providers = project.getProviders();
                    task.addResource(providers, "GradleStart.java");
                    task.addResource(providers, "GradleStartServer.java");
                    task.addResource(providers, "net/minecraftforge/gradle/GradleStartCommon.java");
                    task.addResource(providers, "net/minecraftforge/gradle/OldPropertyMapSerializer.java");
                    task.addResource(providers, "net/minecraftforge/gradle/tweakers/CoremodTweaker.java");
                    task.addResource(providers, "net/minecraftforge/gradle/tweakers/AccessTransformerTweaker.java");

                    MapProperty<String, String> replacements = task.getReplacementTokens();
                    replacements.put("@@MCVERSION@@", mcExt.getMcVersion());
                    replacements.put("@@ASSETINDEX@@", mcExt.getMcVersion());
                    replacements.put(
                            "@@ASSETSDIR@@", mcTasks.getVanillaAssetsLocation().getPath());
                    replacements.put(
                            "@@NATIVESDIR@@", mcTasks.getNativesDirectory().getPath());
                    replacements.put("@@SRGDIR@@", forgeSrgLocation.getPath());
                    replacements.put(
                            "@@SRG_NOTCH_SRG@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getNotchToSrg)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_NOTCH_MCP@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getNotchToMcp)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_SRG_MCP@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getSrgToMcp)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_MCP_SRG@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getMcpToSrg)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@SRG_MCP_NOTCH@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getMcpToNotch)
                                    .map(RegularFile::getAsFile)
                                    .map(File::getPath));
                    replacements.put(
                            "@@CSVDIR@@",
                            taskGenerateForgeSrgMappings
                                    .flatMap(GenSrgMappingsTask::getFieldsCsv)
                                    .map(f -> f.getAsFile().getParentFile().getPath()));
                    replacements.put("@@CLIENTTWEAKER@@", "cpw.mods.fml.common.launcher.FMLTweaker");
                    replacements.put("@@SERVERTWEAKER@@", "cpw.mods.fml.common.launcher.FMLServerTweaker");
                    replacements.put("@@BOUNCERCLIENT@@", "net.minecraft.launchwrapper.Launch");
                    replacements.put("@@BOUNCERSERVER@@", "net.minecraft.launchwrapper.Launch");
                });

        launcherSources = sourceSets.create(SOURCE_SET_LAUNCHER, sourceSet -> {
            sourceSet.setCompileClasspath(patchedConfiguration);
            sourceSet.setRuntimeClasspath(patchedConfiguration);
            sourceSet
                    .getJava()
                    .setSrcDirs(project.files(launcherSourcesLocation).builtBy(taskCreateLauncherFiles));
        });
        javaExt.getSourceSets().add(launcherSources);
        project.getTasks().named("compileMcLauncherJava", JavaCompile.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCreateLauncherFiles);
            configureMcJavaCompilation(task);
        });

        packagedMcLauncherLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "mclauncher.jar");
        taskPackageMcLauncher = project.getTasks().register("packageMcLauncher", Jar.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.dependsOn(taskCreateLauncherFiles, launcherSources.getClassesTaskName());
            task.getArchiveVersion().set(mcExt.getMcVersion());
            task.getArchiveBaseName().set(StringUtils.removeEnd(packagedMcLauncherLocation.getName(), ".jar"));
            task.getDestinationDirectory().set(packagedMcLauncherLocation.getParentFile());

            task.from(launcherSources.getOutput());
            task.from(project.getTasks().named(launcherSources.getClassesTaskName()));
        });

        injectedSourcesLocation = FileUtils.getFile(project.getBuildDir(), "generated", "sources", "injectTags");
        taskInjectTags = project.getTasks().register("injectTags", InjectTagsTask.class, task -> {
            task.setGroup(TASK_GROUP_INTERNAL);
            task.getOutputDir().set(injectedSourcesLocation);
            task.getTags().set(mcExt.getInjectedTags());
        });
        injectedSourceSet = sourceSets.create("injectedTags", set -> {
            set.getJava().setSrcDirs(project.files(injectedSourcesLocation).builtBy(taskInjectTags));
        });
        project.getTasks()
                .named(injectedSourceSet.getCompileJavaTaskName())
                .configure(task -> task.dependsOn(taskInjectTags));
        {
            final SourceSet mainSet = sourceSets.getByName("main");
            mainSet.setCompileClasspath(mainSet.getCompileClasspath().plus(injectedSourceSet.getOutput()));
            mainSet.setRuntimeClasspath(mainSet.getRuntimeClasspath().plus(injectedSourceSet.getOutput()));
            project.getTasks()
                    .named("jar", Jar.class)
                    .configure(task -> task.from(injectedSourceSet.getOutput().getAsFileTree()));
        }

        taskRunClient = project.getTasks().register("runClient", RunMinecraftTask.class, Side.CLIENT);
        taskRunClient.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the deobfuscated client with your mod");
            task.dependsOn(
                    launcherSources.getClassesTaskName(),
                    mcTasks.getTaskDownloadVanillaAssets(),
                    taskPackagePatchedMc,
                    "jar");

            task.classpath(project.getTasks().named("jar"));
            task.classpath(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            task.classpath(taskPackageMcLauncher);
            task.classpath(taskPackagePatchedMc);
            task.classpath(patchedConfiguration);
            task.getMainClass().set("GradleStart");
        });

        taskRunServer = project.getTasks().register("runServer", RunMinecraftTask.class, Side.SERVER);
        taskRunServer.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the deobfuscated server with your mod");
            task.dependsOn(launcherSources.getClassesTaskName(), taskPackagePatchedMc, "classes");

            task.classpath(project.getTasks().named("jar"));
            task.classpath(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            task.classpath(taskPackageMcLauncher);
            task.classpath(taskPackagePatchedMc);
            task.classpath(patchedConfiguration);
            task.getMainClass().set("GradleStartServer");
        });

        // The default jar is deobfuscated, specify the correct classifier for it
        project.getTasks().named("jar", Jar.class).configure(task -> {
            task.getArchiveClassifier().set("dev");
        });
        for (final String name : new String[] {
            JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME,
            JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME,
            JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME,
            JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
            JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME,
            JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME
        }) {
            project.getConfigurations()
                    .getByName(name)
                    .getAttributes()
                    .attribute(
                            ObfuscationAttribute.OBFUSCATION_ATTRIBUTE,
                            ObfuscationAttribute.getMcp(project.getObjects()));
        }

        // Add a reobfuscation task rule
        project.getTasks().addRule("Pattern: reobf<JarTaskName>: Reobfuscate a modded jar", taskName -> {
            if (!taskName.startsWith("reobf")) {
                return;
            }
            final String subjectTaskName = StringUtils.uncapitalize(StringUtils.removeStart(taskName, "reobf"));
            final TaskProvider<Jar> subjectTask;
            try {
                subjectTask = project.getTasks().named(subjectTaskName, Jar.class);
            } catch (UnknownTaskException ute) {
                project.getLogger()
                        .warn("Couldn't find a Jar task named " + subjectTaskName + " for automatic reobfuscation");
                return;
            }
            project.getTasks().register(taskName, ReobfuscatedJar.class, task -> {
                task.setGroup(TASK_GROUP_USER);
                task.setDescription("Reobfuscate the output of the `" + subjectTaskName + "` task to SRG mappings");
                task.dependsOn(
                        taskExtractMcpData,
                        taskExtractForgeUserdev,
                        taskGenerateForgeSrgMappings,
                        taskPackagePatchedMc,
                        subjectTask);

                task.setInputJarFromTask(subjectTask);
                task.getMcVersion().set(mcExt.getMcVersion());
                task.getSrg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMcpToSrg));
                task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
                task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
                task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                task.getRecompMcJar().set(taskPackagePatchedMc.flatMap(Jar::getArchiveFile));
                task.getReferenceClasspath()
                        .from(project.getConfigurations()
                                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                                .fileCollection(Specs.SATISFIES_ALL));
                final ConfigurableFileCollection refCp = task.getReferenceClasspath();
                refCp.from(project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
                refCp.from(project.getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME));
                refCp.from(taskPackageMcLauncher);
                refCp.from(taskPackagePatchedMc);
                refCp.from(patchedConfiguration);
            });
        });

        // Initialize a reobf task for the default jar
        final TaskProvider<ReobfuscatedJar> taskReobfJar = project.getTasks().named("reobfJar", ReobfuscatedJar.class);
        if (project.getTasks().getNames().contains(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)) {
            project.getTasks()
                    .named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
                    .configure(task -> task.dependsOn(taskReobfJar));
        }
        reobfJarConfiguration = project.getConfigurations().create("reobfJarConfiguration");
        {
            // Based on org.gradle.api.plugins.internal.JvmPluginsHelper.configureDocumentationVariantWithArtifact
            reobfJarConfiguration.setVisible(true);
            reobfJarConfiguration.setCanBeConsumed(true);
            reobfJarConfiguration.setCanBeResolved(false);
            reobfJarConfiguration.setDescription("Reobfuscated jar");

            project.afterEvaluate(p -> {
                final DependencyHandler deps = project.getDependencies();
                final Configuration parentUnresolved =
                        project.getConfigurations().getByName(JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME);
                final Configuration parentResolved = project.getConfigurations()
                        .detachedConfiguration(
                                parentUnresolved.getAllDependencies().toArray(new Dependency[0]));
                final AttributeContainer parentAttrs = parentResolved.getAttributes();
                parentAttrs.attribute(
                        ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getMcp(objectFactory));
                parentAttrs.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
                parentAttrs.attribute(
                        Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.LIBRARY));
                parentAttrs.attribute(
                        Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EXTERNAL));

                parentResolved.setCanBeConsumed(false);
                parentResolved.setCanBeResolved(true);
                parentResolved.resolve();
                final Set<String> excludedGroups =
                        mcExt.getGroupsToExcludeFromAutoReobfMapping().get();
                final HashSet<ResolvedDependency> visited = new HashSet<>();
                final Deque<ResolvedDependency> toVisit = new ArrayDeque<>(64);
                toVisit.addAll(parentResolved.getResolvedConfiguration().getFirstLevelModuleDependencies());
                while (!toVisit.isEmpty()) {
                    final ResolvedDependency dep = toVisit.removeFirst();
                    if (!visited.add(dep)
                            || dep.getModuleGroup() == null
                            || dep.getModuleGroup().isEmpty()) {
                        continue;
                    }
                    if (excludedGroups.contains(dep.getModuleGroup())) {
                        continue;
                    }
                    toVisit.addAll(dep.getChildren());

                    ModuleDependency mDep = (ModuleDependency) deps.create(ImmutableMap.of(
                            "group",
                            dep.getModuleGroup(),
                            "name",
                            dep.getModuleName(),
                            "version",
                            dep.getModuleVersion()));
                    // The artifacts only exist for dependencies with a classifier (eg :dev)
                    LinkedHashSet<DependencyArtifact> newArtifacts = new LinkedHashSet<>();
                    for (ResolvedArtifact artifact : dep.getModuleArtifacts()) {
                        String classifier = artifact.getClassifier();
                        if ("dev".equalsIgnoreCase(classifier) || "deobf".equalsIgnoreCase(classifier)) {
                            classifier = "";
                        }
                        if ("api".equalsIgnoreCase(classifier)) {
                            continue;
                        }
                        newArtifacts.add(new DefaultDependencyArtifact(
                                artifact.getName(), artifact.getType(), artifact.getExtension(), classifier, null));
                    }
                    mDep.setTransitive(false);
                    mDep.getArtifacts().clear();
                    mDep.getArtifacts().addAll(newArtifacts);
                    reobfJarConfiguration.getDependencies().add(mDep);
                }
            });
            final AttributeContainer attributes = reobfJarConfiguration.getAttributes();
            attributes.attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, Category.LIBRARY));
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.class, Bundling.EXTERNAL));
            project.afterEvaluate(p -> {
                attributes.attribute(
                        TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE,
                        mcExt.getJavaCompatibilityVersion().get());
            });
            attributes.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objectFactory.named(LibraryElements.class, LibraryElements.JAR));
            attributes.attribute(
                    ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getSrg(objectFactory));
            project.getArtifacts().add(reobfJarConfiguration.getName(), taskReobfJar);

            final Configuration reobfElements = project.getConfigurations().create("reobfElements");
            reobfElements.setCanBeResolved(false);
            reobfElements.setCanBeConsumed(true);
            reobfElements.getOutgoing().artifact(taskReobfJar);
            attributes.keySet().forEach(attr -> reobfElements
                    .getAttributes()
                    .attribute((Attribute) attr, attributes.getAttribute(attr)));

            SoftwareComponent javaComponent = project.getComponents().getByName("java");
            if (javaComponent instanceof AdhocComponentWithVariants) {
                AdhocComponentWithVariants java = (AdhocComponentWithVariants) javaComponent;
                java.addVariantsFromConfiguration(reobfElements, ConfigurationVariantDetails::mapToOptional);
            }
        }

        binaryPatchedMcLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "binpatchedmc.jar");
        taskInstallBinaryPatchedVersion = project.getTasks()
                .register("installBinaryPatchedVersion", BinaryPatchJarTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskExtractForgeUserdev, taskMergeVanillaSidedJars);
                    task.setDescription("currently unused");
                    task.getInputJar().set(taskMergeVanillaSidedJars.flatMap(MergeSidedJarsTask::getMergedJar));
                    task.getOutputJar().set(binaryPatchedMcLocation);
                    task.getPatchesLzma().set(userdevFile("devbinpatches.pack.lzma"));
                    task.getExtraClassesJar().set(userdevFile("binaries.jar"));
                    task.getExtraResourcesTree().from(userdevDir("src/main/resources"));
                });

        srgBinaryPatchedMcLocation = FileUtils.getFile(project.getBuildDir(), RFG_DIR, "srg_binpatchedmc.jar");
        taskSrgifyBinaryPatchedVersion = project.getTasks()
                .register("srgifyBinpatchedJar", DeobfuscateTask.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskInstallBinaryPatchedVersion, taskGenerateForgeSrgMappings);
                    task.setDescription("currently unused");
                    task.getSrgFile().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getNotchToSrg));
                    task.getExceptorJson().set(userdevFile("conf/exceptor.json"));
                    task.getExceptorCfg().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getSrgExc));
                    task.getInputJar().set(taskInstallBinaryPatchedVersion.flatMap(BinaryPatchJarTask::getOutputJar));
                    task.getOutputJar().set(srgBinaryPatchedMcLocation);
                    task.getFieldCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getFieldsCsv));
                    task.getMethodCsv().set(taskGenerateForgeSrgMappings.flatMap(GenSrgMappingsTask::getMethodsCsv));
                    task.getIsApplyingMarkers().set(true);
                    // Configured in afterEvaluate()
                    task.getAccessTransformerFiles().setFrom(deobfuscationATs);
                });

        obfRuntimeClasspathConfiguration = project.getConfigurations().create("obfuscatedRuntimeClasspath");
        obfRuntimeClasspathConfiguration.setCanBeConsumed(false);
        obfRuntimeClasspathConfiguration.extendsFrom(reobfJarConfiguration);
        obfRuntimeClasspathConfiguration
                .getAttributes()
                .attribute(
                        ObfuscationAttribute.OBFUSCATION_ATTRIBUTE, ObfuscationAttribute.getSrg(project.getObjects()));

        final File obfRunFolder = new File(mcTasks.getRunDirectory(), "obfuscated/");
        final TaskProvider<Copy> taskPrepareObfMods = project.getTasks()
                .register("prepareObfModsFolder", Copy.class, task -> {
                    task.setGroup(TASK_GROUP_INTERNAL);
                    task.dependsOn(taskPackageMcLauncher, taskPackagePatchedMc, taskReobfJar);

                    task.from(taskReobfJar);
                    task.from(obfRuntimeClasspathConfiguration);

                    task.into(new File(obfRunFolder, "mods/"));

                    task.doFirst(t -> {
                        final File obfModsFolder = task.getDestinationDir();
                        final File[] children = obfModsFolder.listFiles();
                        if (children != null) {
                            for (final File child : children) {
                                FileUtils.deleteQuietly(child);
                            }
                        }
                    });
                });

        taskRunObfClient = project.getTasks().register("runObfClient", RunMinecraftTask.class, Side.CLIENT);
        taskRunObfClient.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the Forge obfuscated client with your mod");
            task.dependsOn(
                    mcTasks.getTaskDownloadVanillaJars(),
                    mcTasks.getTaskDownloadVanillaAssets(),
                    taskReobfJar,
                    taskPrepareObfMods);

            task.setWorkingDir(obfRunFolder);
            task.systemProperty("retrofuturagradle.reobfDev", true);
            task.classpath(forgeUniversalConfiguration);
            task.classpath(mcTasks.getVanillaClientLocation());
            task.classpath(patchedConfiguration);
            task.getMainClass().set("net.minecraft.launchwrapper.Launch");
            task.getTweakClasses().add("cpw.mods.fml.common.launcher.FMLTweaker");
        });

        taskRunObfServer = project.getTasks().register("runObfServer", RunMinecraftTask.class, Side.SERVER);
        taskRunObfServer.configure(task -> {
            task.setup(project);
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Runs the Forge obfuscated server with your mod");
            task.dependsOn(mcTasks.getTaskDownloadVanillaJars(), taskReobfJar, taskPrepareObfMods);

            task.setWorkingDir(obfRunFolder);
            task.systemProperty("retrofuturagradle.reobfDev", true);
            task.classpath(project.getTasks().named("reobfJar"));
            task.classpath(forgeUniversalConfiguration);
            task.classpath(mcTasks.getVanillaServerLocation());
            task.classpath(patchedConfiguration);
            task.getMainClass().set("cpw.mods.fml.relauncher.ServerLaunchWrapper");
        });

        // Mostly for compat with FG
        project.getTasks().register("setupCIWorkspace", DefaultTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Prepares everything for mod building on a CI server");
            task.dependsOn(taskPackagePatchedMc, taskPackageMcLauncher);
        });
        project.getTasks().register("setupDecompWorkspace", DefaultTask.class, task -> {
            task.setGroup(TASK_GROUP_USER);
            task.setDescription("Prepares everything for mod building in a dev environment");
            task.dependsOn(taskPackagePatchedMc, taskPackageMcLauncher, mcTasks.getTaskDownloadVanillaAssets());
        });
    }

    public void configureMcJavaCompilation(JavaCompile task) {
        task.getModularity().getInferModulePath().set(false);
        task.getOptions().setEncoding("UTF-8");
        task.getOptions().setFork(true);
        task.getOptions().setWarnings(false);
        task.setSourceCompatibility(JavaVersion.VERSION_1_8.toString());
        task.setTargetCompatibility(JavaVersion.VERSION_1_8.toString());
        task.getJavaCompiler().set(mcExt.getToolchainCompiler());
    }

    private void afterEvaluate() {
        final DependencyHandler deps = project.getDependencies();

        deps.add(
                mcpMappingDataConfiguration.getName(),
                ImmutableMap.of(
                        "group",
                        "de.oceanlabs.mcp",
                        "name",
                        "mcp_" + mcExt.getMcpMappingChannel().get(),
                        "version",
                        mcExt.getMcpMappingVersion().get() + "-"
                                + mcExt.getMcVersion().get(),
                        "ext",
                        "zip"));

        if (mcExt.getSkipSlowTasks().get()) {
            taskDeobfuscateMergedJarToSrg.configure(t -> t.onlyIf(
                    "skipping slow task",
                    p -> !t.getOutputJar().get().getAsFile().exists()));
            taskDecompileSrgJar.configure(t -> t.onlyIf(
                    "skipping slow task",
                    p -> !t.getOutputJar().get().getAsFile().exists()));
            taskPatchDecompiledJar.configure(t -> t.onlyIf(
                    "skipping slow task",
                    p -> !t.getOutputJar().get().getAsFile().exists()));
        }

        deps.add(forgeUserdevConfiguration.getName(), "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10:userdev");
        deps.add(
                forgeUniversalConfiguration.getName(), "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10:universal");
        if (mcExt.getTagReplacementFiles().isPresent()
                && !mcExt.getTagReplacementFiles().get().isEmpty()) {
            final File replacementPropFile = new File(injectedSourcesLocation.getParentFile(), "injectTags.resources");
            taskInjectTags.configure(task -> {
                task.getOutputs().file(replacementPropFile);
                task.doLast("Generate tag injection resource file", t -> {
                    final Properties props = new Properties();
                    int i = 0;
                    for (String pattern : mcExt.getTagReplacementFiles().get()) {
                        props.setProperty("files." + i, pattern);
                    }
                    for (Map.Entry<String, Object> value : task.getTags().get().entrySet()) {
                        props.setProperty(
                                "replacements." + value.getKey(),
                                value.getValue().toString());
                    }
                    try (FileOutputStream fos = new FileOutputStream(replacementPropFile);
                            BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                        props.store(bos, "");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            });
            // Inject a javac & scalac plugin
            final JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
            final Configuration rfgJavacCfg = project.getConfigurations().create("rfgJavacPlugin", cfg -> {
                cfg.setCanBeConsumed(false);
                cfg.setCanBeResolved(true);
            });
            final Dependency rfgJavacPlugin = project.getDependencies()
                    .add(rfgJavacCfg.getName(), "com.gtnewhorizons:rfg-javac-plugin:" + BuildConfig.PLUGIN_VERSION);
            project.getConfigurations().getByName("annotationProcessor").extendsFrom(rfgJavacCfg);
            final URI replacementsUri = replacementPropFile.toURI();
            project.getTasks().named("compileJava", JavaCompile.class).configure(task -> {
                task.getOptions()
                        .getCompilerArgs()
                        .add("-Xplugin:RetrofuturagradleTokenReplacement " + replacementsUri.toASCIIString());
                task.getOptions().setFork(true);
                if (javaExt.getToolchain().getLanguageVersion().get().asInt() > 8) {
                    final List<String> jargs = Arrays.asList(
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                            "--add-exports",
                            "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                            "--add-opens",
                            "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                            "--add-opens",
                            "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED");
                    task.getOptions().getForkOptions().getJvmArgs().addAll(jargs);
                }
            });
            if (project.getPluginManager().hasPlugin("scala")) {
                // Configure the Scala task lazily to avoid failure if it doesn't exist
                project.getTasks().withType(ScalaCompile.class).configureEach(task -> {
                    if (!task.getName().equals("compileScala")) {
                        return;
                    }
                    task.setScalaCompilerPlugins(task.getScalaCompilerPlugins().plus(rfgJavacCfg));
                    if (task.getScalaCompileOptions().getAdditionalParameters() == null) {
                        task.getScalaCompileOptions().setAdditionalParameters(new ArrayList<>());
                    }
                    final String scParam =
                            "-P:RetrofuturagradleScalaTokenReplacement:" + replacementsUri.toASCIIString();
                    // It can be an immutable list
                    final List<String> scArgs =
                            new ArrayList<>(task.getScalaCompileOptions().getAdditionalParameters());
                    scArgs.add(scParam);
                    task.getScalaCompileOptions().setAdditionalParameters(scArgs);
                    task.getOptions().setFork(true);
                });
            }
        }
        if (mcExt.getUsesFml().get()) {
            deobfuscationATs.builtBy(taskExtractForgeUserdev);
            deobfuscationATs.from(userdevFile(Constants.PATH_USERDEV_FML_ACCESS_TRANFORMER));

            taskPatchDecompiledJar.configure(task -> {
                task.getPatches().builtBy(taskExtractForgeUserdev);
                task.getInjectionDirectories().builtBy(taskExtractForgeUserdev);
                task.getPatches().from(userdevFile("fmlpatches.zip"));
                task.getInjectionDirectories().from(userdevDir("src/main/java"));
                task.getInjectionDirectories().from(userdevDir("src/main/resources"));
            });

            final String PATCHED_MC_CFG = patchedConfiguration.getName();
            // LaunchWrapper brings in its own lwjgl version which we want to override
            ((ModuleDependency) deps.add(PATCHED_MC_CFG, "net.minecraft:launchwrapper:1.12")).setTransitive(false);
            deps.add(PATCHED_MC_CFG, "com.google.code.findbugs:jsr305:1.3.9");
            deps.add(PATCHED_MC_CFG, "org.ow2.asm:asm-debug-all:5.0.3");
            deps.add(PATCHED_MC_CFG, "com.typesafe.akka:akka-actor_2.11:2.3.3");
            deps.add(PATCHED_MC_CFG, "com.typesafe:config:1.2.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-actors-migration_2.11:1.1.0");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-compiler:2.11.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang.plugins:scala-continuations-library_2.11:1.0.2");
            deps.add(PATCHED_MC_CFG, "org.scala-lang.plugins:scala-continuations-plugin_2.11.1:1.0.2");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-library:2.11.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-parser-combinators_2.11:1.0.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-reflect:2.11.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-swing_2.11:1.0.1");
            deps.add(PATCHED_MC_CFG, "org.scala-lang:scala-xml_2.11:1.0.2");
            deps.add(PATCHED_MC_CFG, "lzma:lzma:0.0.1");

            if (mcExt.getUsesForge().get()) {
                deobfuscationATs.from(userdevDir(Constants.PATH_USERDEV_FORGE_ACCESS_TRANFORMER));

                taskPatchDecompiledJar.configure(task -> {
                    task.getPatches().from(userdevDir("forgepatches.zip"));
                });

                final SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
                final SourceSet mainSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                final ConfigurableFileCollection mcCp = project.getObjects().fileCollection();
                mcCp.from(launcherSources.getOutput());
                mcCp.from(patchedMcSources.getOutput());
                mainSet.setCompileClasspath(mainSet.getCompileClasspath().plus(mcCp));
                mainSet.setRuntimeClasspath(mainSet.getRuntimeClasspath().plus(mcCp));
            }
        }
    }

    public Configuration getMcpMappingDataConfiguration() {
        return mcpMappingDataConfiguration;
    }

    public Configuration getForgeUserdevConfiguration() {
        return forgeUserdevConfiguration;
    }

    public File getFernflowerLocation() {
        return fernflowerLocation;
    }

    public TaskProvider<Download> getTaskDownloadFernflower() {
        return taskDownloadFernflower;
    }

    public File getMcpDataLocation() {
        return mcpDataLocation;
    }

    public TaskProvider<Copy> getTaskExtractMcpData() {
        return taskExtractMcpData;
    }

    public File getForgeUserdevLocation() {
        return forgeUserdevLocation;
    }

    public TaskProvider<Copy> getTaskExtractForgeUserdev() {
        return taskExtractForgeUserdev;
    }

    public File getForgeSrgLocation() {
        return forgeSrgLocation;
    }

    public TaskProvider<GenSrgMappingsTask> getTaskGenerateForgeSrgMappings() {
        return taskGenerateForgeSrgMappings;
    }

    public File getMergedVanillaJarLocation() {
        return mergedVanillaJarLocation;
    }

    public TaskProvider<MergeSidedJarsTask> getTaskMergeVanillaSidedJars() {
        return taskMergeVanillaSidedJars;
    }

    public File getSrgMergedJarLocation() {
        return srgMergedJarLocation;
    }

    public TaskProvider<DeobfuscateTask> getTaskDeobfuscateMergedJarToSrg() {
        return taskDeobfuscateMergedJarToSrg;
    }

    public ConfigurableFileCollection getDeobfuscationATs() {
        return deobfuscationATs;
    }

    public TaskProvider<DecompileTask> getTaskDecompileSrgJar() {
        return taskDecompileSrgJar;
    }

    public File getDecompiledSrgLocation() {
        return decompiledSrgLocation;
    }

    public TaskProvider<PatchSourcesTask> getTaskPatchDecompiledJar() {
        return taskPatchDecompiledJar;
    }

    public File getPatchedSourcesLocation() {
        return patchedSourcesLocation;
    }

    public TaskProvider<RemapSourceJarTask> getTaskRemapDecompiledJar() {
        return taskRemapDecompiledJar;
    }

    public File getRemappedSourcesLocation() {
        return remappedSourcesLocation;
    }

    public TaskProvider<Copy> getTaskDecompressDecompiledSources() {
        return taskDecompressDecompiledSources;
    }

    public File getDecompressedSourcesLocation() {
        return decompressedSourcesLocation;
    }

    public Configuration getPatchedConfiguration() {
        return patchedConfiguration;
    }

    public SourceSet getPatchedMcSources() {
        return patchedMcSources;
    }

    public TaskProvider<JavaCompile> getTaskBuildPatchedMc() {
        return taskBuildPatchedMc;
    }

    public File getPackagedMcLocation() {
        return packagedMcLocation;
    }

    public TaskProvider<Jar> getTaskPackagePatchedMc() {
        return taskPackagePatchedMc;
    }

    public Configuration getForgeUniversalConfiguration() {
        return forgeUniversalConfiguration;
    }

    public File getLauncherSourcesLocation() {
        return launcherSourcesLocation;
    }

    public TaskProvider<CreateLauncherFiles> getTaskCreateLauncherFiles() {
        return taskCreateLauncherFiles;
    }

    public SourceSet getLauncherSources() {
        return launcherSources;
    }

    public File getPackagedMcLauncherLocation() {
        return packagedMcLauncherLocation;
    }

    public TaskProvider<Jar> getTaskPackageMcLauncher() {
        return taskPackageMcLauncher;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunClient() {
        return taskRunClient;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunServer() {
        return taskRunServer;
    }

    public File getBinaryPatchedMcLocation() {
        return binaryPatchedMcLocation;
    }

    public TaskProvider<BinaryPatchJarTask> getTaskInstallBinaryPatchedVersion() {
        return taskInstallBinaryPatchedVersion;
    }

    public File getSrgBinaryPatchedMcLocation() {
        return srgBinaryPatchedMcLocation;
    }

    public TaskProvider<DeobfuscateTask> getTaskSrgifyBinaryPatchedVersion() {
        return taskSrgifyBinaryPatchedVersion;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunObfClient() {
        return taskRunObfClient;
    }

    public TaskProvider<RunMinecraftTask> getTaskRunObfServer() {
        return taskRunObfServer;
    }

    public Configuration getObfRuntimeClasspathConfiguration() {
        return obfRuntimeClasspathConfiguration;
    }

    public Configuration getReobfJarConfiguration() {
        return reobfJarConfiguration;
    }
}
