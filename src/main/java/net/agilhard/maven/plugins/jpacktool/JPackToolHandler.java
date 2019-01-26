package net.agilhard.maven.plugins.jpacktool;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.util.cli.Commandline;

public class JPackToolHandler extends CollectJarsHandler {

    /**
     * The jdeps Java Tool Executable.
     */
    private String jdepsExecutable;

    private boolean generateAutomaticJdeps;

    private boolean generateClassPathJdeps;

    private boolean generateModuleJdeps;

    private List<File> classPathElements = new ArrayList<>();

    private List<String> warnings = new ArrayList<>();
    
    private List<String> errors = new ArrayList<>();
    
    private List<String> jarsOnClassPath = new ArrayList<>();

    protected List<String> systemModules = new ArrayList<>();;
    protected List<String> linkedSystemModules = new ArrayList<>();;

    private List<String> allModules = new ArrayList<>();
    private List<String> linkedModules = new ArrayList<>();
    private List<String> automaticModules = new ArrayList<>();

    private List<String> nodeStrings = new ArrayList<>();

    private Map<String, List<String>> allModulesMap = new HashMap<>();
    private Map<String, List<String>> linkedModulesMap = new HashMap<>();;
    private Map<String, List<String>> automaticModulesMap = new HashMap<>();;
    private Map<String, List<String>> linkedSystemModulesMap = new HashMap<>();;

    public JPackToolHandler(AbstractToolMojo mojo, DependencyGraphBuilder dependencyGraphBuilder,
            File outputDirectoryAutomaticJars, File outputDirectoryClasspathJars, File outputDirectoryModules,
            String jdepsExecutable, boolean generateAutomaticJdeps, boolean generateClassPathJdeps,
            boolean generateModuleJdeps) throws MojoExecutionException {

        super(mojo, dependencyGraphBuilder, outputDirectoryAutomaticJars, outputDirectoryClasspathJars,
                outputDirectoryModules, true);

        this.jdepsExecutable = jdepsExecutable;

        this.generateAutomaticJdeps = generateAutomaticJdeps;
        this.generateClassPathJdeps = generateClassPathJdeps;
        this.generateModuleJdeps = generateModuleJdeps;

        this.systemModules = mojo.getSystemModules();
    }

    /**
     * Convert a list into a
     * 
     * @param modules The list of modules.
     * @return The string with the module list which is separated by {@code ,}.
     */
    protected String getColonSeparatedList(final Collection<File> elements) throws MojoFailureException {
        final StringBuilder sb = new StringBuilder();
        for (final File element : elements) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            try {
                sb.append(element.getCanonicalPath());
            } catch (IOException e) {
                throw new MojoFailureException("error getting path");
            }
        }
        return sb.toString();
    }

    protected Commandline createJDepsCommandLine(File sourceFile) throws MojoFailureException {
        final Commandline cmd = new Commandline();

        if (this.classPathElements.size() > 0) {
            cmd.createArg().setValue("--class-path");
            String s = this.getColonSeparatedList(this.classPathElements);
            cmd.createArg().setValue(s);
        }

        if ((outputDirectoryAutomaticJars != null) || (outputDirectoryModules != null)) {
            cmd.createArg().setValue("--module-path");
            StringBuilder sb = new StringBuilder();
            if (outputDirectoryModules != null) {
                try {
                    sb.append(outputDirectoryModules.getCanonicalPath());
                } catch (IOException e) {
                    throw new MojoFailureException("error getting path");
                }
            }
            if (outputDirectoryAutomaticJars != null) {
                if (outputDirectoryModules != null) {
                    sb.append(':');
                }
                try {
                    sb.append(outputDirectoryAutomaticJars.getCanonicalPath());
                } catch (IOException e) {
                    throw new MojoFailureException("error getting path");
                }
            }
            cmd.createArg().setValue(sb.toString());

        }

        cmd.createArg().setValue("--print-module-deps");
        cmd.createArg().setValue("--ignore-missing-deps");

        try {
            String s = sourceFile.getCanonicalPath();
            cmd.createArg().setValue(s);
        } catch (IOException e) {
            throw new MojoFailureException("error getting path");
        }

        return cmd;
    }

    protected void generateJdeps(String nodeString, File sourceFile, File targetFile)
            throws MojoExecutionException, MojoFailureException {

        Commandline cmd = this.createJDepsCommandLine(sourceFile);
        cmd.setExecutable(jdepsExecutable);
        String name = sourceFile.getName();
        int i = name.lastIndexOf('-');
        name = name.substring(0, i) + ".jdeps";

        File file = new File(targetFile, name);
        try (FileOutputStream fout = new FileOutputStream(file)) {
            executeCommand(cmd, fout);
        } catch (IOException ioe) {
            getLog().error("error creating .jdeps file");
        } catch (MojoExecutionException mee) {
            throw mee;
        }

        List<String> deps = new ArrayList<>();
        List<String> automaticDeps = new ArrayList<>();
        List<String> linkedDeps = new ArrayList<>();
        List<String> linkedSystemDeps = new ArrayList<>();

        // fill with empty values first in case there is an error later
        
        allModulesMap.put(nodeString, deps);
        automaticModulesMap.put(nodeString, automaticDeps);
        linkedModulesMap.put(nodeString, linkedDeps);
        linkedSystemModulesMap.put(nodeString, linkedSystemDeps);
        
        try (FileReader fr = new FileReader(file); BufferedReader br = new BufferedReader(fr)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Warning:")) {
                    if ( ! warnings.contains(line) ) {
                        warnings.add(line);
                    }
                } else if (line.startsWith("Error:")) {
                        if ( ! errors.contains(line) ) {
                            errors.add(line);
                        }
                } else {
                    for (String dep : line.split(",")) {
                        if (!deps.contains(dep)) {
                            deps.add(dep);
                        }
                        if (!allModules.contains(dep)) {
                            allModules.add(dep);
                        }
                        if (systemModules.contains(dep)) {
                            if (!linkedSystemModules.contains(dep)) {
                                linkedSystemModules.add(dep);
                            }
                            if (!linkedSystemDeps.contains(dep)) {
                                linkedSystemDeps.add(dep);
                            }
                        } else {
                            if (automaticModules.contains(dep)) {
                                if (!automaticDeps.contains(dep)) {
                                    automaticDeps.add(dep);
                                }
                            } else {
                                if (!linkedModules.contains(dep)) {
                                    linkedModules.add(dep);
                                }
                                if (!linkedDeps.contains(dep)) {
                                    linkedDeps.add(dep);
                                }
                            }
                        }

                    }
                }
            }
        } catch (IOException ioe) {
            throw new MojoExecutionException("i/o error", ioe);
        }

        allModulesMap.put(nodeString, deps);
        automaticModulesMap.put(nodeString, automaticDeps);
        linkedModulesMap.put(nodeString, linkedDeps);
        linkedSystemModulesMap.put(nodeString, linkedSystemDeps);
    }

    protected void handleNonModJar(final DependencyNode dependencyNode, final Artifact artifact,
            Map.Entry<File, JavaModuleDescriptor> entry) throws MojoExecutionException, MojoFailureException {

        getLog().debug("handleNonModJar:" + artifact.getFile());

        boolean isAutomatic = (entry == null || entry.getValue() == null) ? false : entry.getValue().isAutomatic();

        String nodeString = dependencyNode.toNodeString();

        if (!nodeStrings.contains(nodeString)) {
            nodeStrings.add(nodeString);
        }

        if (isAutomatic) {
            try (JarFile jarFile = new JarFile(artifact.getFile())) {
                Manifest manifest = jarFile.getManifest();
                if (manifest == null) {
                    isAutomatic = false;
                } else {
                    Attributes mainAttributes = manifest.getMainAttributes();
                    isAutomatic = mainAttributes.getValue("Automatic-Module-Name") != null;
                }
            } catch (IOException e) {

                getLog().error("error reading manifest");
                throw new MojoExecutionException("error reading manifest");
            }

        }

        Path path = artifact.getFile().toPath();

        if (Files.isRegularFile(path)) {

            File target = null;
            if (isAutomatic) {
                if (outputDirectoryAutomaticJars != null) {
                    if (generateAutomaticJdeps) {
                        generateJdeps(nodeString, artifact.getFile(), outputDirectoryAutomaticJars);
                    }
                }
            } else {
                if (outputDirectoryClasspathJars != null) {
                    if (generateClassPathJdeps) {
                        generateJdeps(nodeString, artifact.getFile(), outputDirectoryClasspathJars);
                    }
                    target = new File(outputDirectoryClasspathJars, artifact.getFile().getName());

                    if (!classPathElements.contains(target)) {
                        classPathElements.add(target);
                        jarsOnClassPath.add(artifact.getFile().getName());
                    }
                }
            }
        }

        if (isAutomatic) {
            String name = entry.getValue().name();

            if (!automaticModules.contains(name)) {
                automaticModules.add(name);
            }
        }

        super.handleNonModJar(dependencyNode, artifact, entry);

    }

    protected void handleModJar(final DependencyNode dependencyNode, final Artifact artifact,
            Map.Entry<File, JavaModuleDescriptor> entry) throws MojoExecutionException, MojoFailureException {

        getLog().debug("handleModJar:" + artifact.getFile());

        String nodeString = dependencyNode.toNodeString();

        if (!nodeStrings.contains(nodeString)) {
            nodeStrings.add(nodeString);
        }

        if (generateModuleJdeps) {
            generateJdeps(nodeString, artifact.getFile(), outputDirectoryModules);
        }

        String name = entry.getValue().name();

        if (!linkedModules.contains(name)) {
            linkedModules.add(name);
        }

        super.handleModJar(dependencyNode, artifact, entry);

    }

    protected void executeCommand(final Commandline cmd, OutputStream outputStream) throws MojoExecutionException {
        ExecuteCommand.executeCommand(mojo.verbose, this.getLog(), cmd, outputStream);
    }

    public List<File> getClassPathElements() {
        return classPathElements;
    }

    public List<String> getSystemModules() {
        return systemModules;
    }

    public List<String> getLinkedSystemModules() {
        return linkedSystemModules;
    }

    public List<String> getAllModules() {
        return allModules;
    }

    public List<String> getLinkedModules() {
        return linkedModules;
    }

    public List<String> getAutomaticModules() {
        return automaticModules;
    }

    public List<String> getNodeStrings() {
        return nodeStrings;
    }

    public Map<String, List<String>> getAllModulesMap() {
        return allModulesMap;
    }

    public Map<String, List<String>> getLinkedModulesMap() {
        return linkedModulesMap;
    }

    public Map<String, List<String>> getAutomaticModulesMap() {
        return automaticModulesMap;
    }

    public Map<String, List<String>> getLinkedSystemModulesMap() {
        return linkedSystemModulesMap;
    }

    public List<String> getJarsOnClassPath() {
        return jarsOnClassPath;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getErrors() {
        return errors;
    }
    
    
}
