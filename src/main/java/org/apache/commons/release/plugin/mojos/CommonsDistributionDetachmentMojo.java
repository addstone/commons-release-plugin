/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.release.plugin.mojos;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.properties.SortedProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.release.plugin.SharedFunctions;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * The purpose of this Maven mojo is to detach the artifacts generated by the maven-assembly-plugin,
 * which for the Apache Commons Project do not get uploaded to Nexus, and putting those artifacts
 * in the dev distribution location for Apache projects.
 *
 * @author chtompki
 * @since 1.0
 */
@Mojo(name = "detach-distributions",
        defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = true,
        aggregator = true)
public class CommonsDistributionDetachmentMojo extends AbstractMojo {

    /**
     * A list of "artifact types" in the Maven vernacular, to
     * be detached from the deployment. For the time being we want
     * all artifacts generated by the maven-assembly-plugin to be detached
     * from the deployment, namely *-src.zip, *-src.tar.gz, *-bin.zip,
     * *-bin.tar.gz, and the corresponding .asc pgp signatures.
     */
    private static final Set<String> ARTIFACT_TYPES_TO_DETACH;
    static {
        final Set<String> hashSet = new HashSet<>();
        hashSet.add("zip");
        hashSet.add("tar.gz");
        hashSet.add("zip.asc");
        hashSet.add("tar.gz.asc");
        ARTIFACT_TYPES_TO_DETACH = Collections.unmodifiableSet(hashSet);
    }

    /**
     * This list is supposed to hold the Maven references to the aforementioned artifacts so that we
     * can upload them to svn after they've been detached from the Maven deployment.
     */
    private final List<Artifact> detachedArtifacts = new ArrayList<>();

    /**
     * A {@link SortedProperties} of {@link Artifact} → {@link String} containing the sha256 signatures
     * for the individual artifacts, where the {@link Artifact} is represented as:
     * <code>groupId:artifactId:version:type=sha512</code>.
     */
    private final SortedProperties artifactSha512s = new SortedProperties();

    /**
     * The maven project context injection so that we can get a hold of the variables at hand.
     */
    @Parameter(defaultValue = "${project}", required = true)
    private MavenProject project;

    /**
     * The working directory in <code>target</code> that we use as a sandbox for the plugin.
     */
    @Parameter(defaultValue = "${project.build.directory}/commons-release-plugin",
            property = "commons.outputDirectory")
    private File workingDirectory;

    /**
     * The subversion staging url to which we upload all of our staged artifacts.
     */
    @Parameter(defaultValue = "", property = "commons.distSvnStagingUrl")
    private String distSvnStagingUrl;

    /**
     * A parameter to generally avoid running unless it is specifically turned on by the consuming module.
     */
    @Parameter(defaultValue = "false", property = "commons.release.isDistModule")
    private Boolean isDistModule;

    @Override
    public void execute() throws MojoExecutionException {
        if (!isDistModule) {
            getLog().info(
                    "This module is marked as a non distribution or assembly module, and the plugin will not run.");
            return;
        }
        if (StringUtils.isEmpty(distSvnStagingUrl)) {
            getLog().warn("commons.distSvnStagingUrl is not set, the commons-release-plugin will not run.");
            return;
        }
        getLog().info("Detaching Assemblies");
        for (final Object attachedArtifact : project.getAttachedArtifacts()) {
            putAttachedArtifactInSha512Map((Artifact) attachedArtifact);
            if (ARTIFACT_TYPES_TO_DETACH.contains(((Artifact) attachedArtifact).getType())) {
                detachedArtifacts.add((Artifact) attachedArtifact);
            }
        }
        if (detachedArtifacts.isEmpty()) {
            getLog().info("Current project contains no distributions. Not executing.");
            return;
        }
        for (final Artifact artifactToRemove : detachedArtifacts) {
            project.getAttachedArtifacts().remove(artifactToRemove);
        }
        if (!workingDirectory.exists()) {
            SharedFunctions.initDirectory(getLog(), workingDirectory);
        }
        writeAllArtifactsInSha512PropertiesFile();
        copyRemovedArtifactsToWorkingDirectory();
        getLog().info("");
        hashArtifacts();
    }

    /**
     * Takes an attached artifact and puts the signature in the map.
     * @param artifact is a Maven {@link Artifact} taken from the project at start time of mojo.
     * @throws MojoExecutionException if an {@link IOException} occurs when getting the sha512 of the
     *                                artifact.
     */
    private void putAttachedArtifactInSha512Map(final Artifact artifact) throws MojoExecutionException {
        try {
            final String artifactKey = getArtifactKey(artifact);
            try (FileInputStream fis = new FileInputStream(artifact.getFile())) {
                artifactSha512s.put(artifactKey, DigestUtils.sha512Hex(fis));
            }
        } catch (final IOException e) {
            throw new MojoExecutionException(
                "Could not find artifact signature for: "
                    + artifact.getArtifactId()
                    + "-"
                    + artifact.getClassifier()
                    + "-"
                    + artifact.getVersion()
                    + " type: "
                    + artifact.getType(),
                e);
        }
    }

    /**
     * Writes to ./target/commons-release-plugin/sha512.properties the artifact sha512's.
     *
     * @throws MojoExecutionException if we can't write the file due to an {@link IOException}.
     */
    private void writeAllArtifactsInSha512PropertiesFile() throws MojoExecutionException {
        final File propertiesFile = new File(workingDirectory, "sha512.properties");
        getLog().info("Writting " + propertiesFile);
        try (FileOutputStream fileWriter = new FileOutputStream(propertiesFile)) {
            artifactSha512s.store(fileWriter, "Release SHA-512s");
        } catch (final IOException e) {
            throw new MojoExecutionException("Failure to write SHA-512's", e);
        }
    }

    /**
     * A helper method to copy the newly detached artifacts to <code>target/commons-release-plugin</code>
     * so that the {@link CommonsDistributionStagingMojo} can find the artifacts later.
     *
     * @throws MojoExecutionException if some form of an {@link IOException} occurs, we want it
     *                                properly wrapped so that Maven can handle it.
     */
    private void copyRemovedArtifactsToWorkingDirectory() throws MojoExecutionException {
        final String wdAbsolutePath = workingDirectory.getAbsolutePath();
        getLog().info(
                "Copying " + detachedArtifacts.size() + " detached artifacts to working directory " + wdAbsolutePath);
        for (final Artifact artifact: detachedArtifacts) {
            final File artifactFile = artifact.getFile();
            final StringBuilder copiedArtifactAbsolutePath = new StringBuilder(wdAbsolutePath);
            copiedArtifactAbsolutePath.append("/");
            copiedArtifactAbsolutePath.append(artifactFile.getName());
            final File copiedArtifact = new File(copiedArtifactAbsolutePath.toString());
            getLog().info("Copying: " + artifactFile.getName());
            SharedFunctions.copyFile(getLog(), artifactFile, copiedArtifact);
        }
    }

    /**
     *  A helper method that creates sha256  and sha512 signature files for our detached artifacts in the
     *  <code>target/commons-release-plugin</code> directory for the purpose of being uploaded by
     *  the {@link CommonsDistributionStagingMojo}.
     *
     * @throws MojoExecutionException if some form of an {@link IOException} occurs, we want it
     *                                properly wrapped so that Maven can handle it.
     */
    private void hashArtifacts() throws MojoExecutionException {
        for (final Artifact artifact : detachedArtifacts) {
            if (!artifact.getFile().getName().contains("asc")) {
                final String artifactKey = getArtifactKey(artifact);
                try {
                    String digest;
                    // SHA-512
                    digest = artifactSha512s.getProperty(artifactKey.toString());
                    getLog().info(artifact.getFile().getName() + " sha512: " + digest);
                    try (PrintWriter printWriter = new PrintWriter(
                            getSha512FilePath(workingDirectory, artifact.getFile()))) {
                        printWriter.println(digest);
                    }
                } catch (final IOException e) {
                    throw new MojoExecutionException("Could not sign file: " + artifact.getFile().getName(), e);
                }
            }
        }
    }

    /**
     * A helper method to create a file path for the <code>sha512</code> signature file from a given file.
     *
     * @param directory is the {@link File} for the directory in which to make the <code>.sha512</code> file.
     * @param file the {@link File} whose name we should use to create the <code>.sha512</code> file.
     * @return a {@link String} that is the absolute path to the <code>.sha512</code> file.
     */
    private String getSha512FilePath(final File directory, final File file) {
        final StringBuilder buffer = new StringBuilder(directory.getAbsolutePath());
        buffer.append("/");
        buffer.append(file.getName());
        buffer.append(".sha512");
        return buffer.toString();
    }

    /**
     * Generates the unique artifact key for storage in our sha256 map and sha512 map. For example,
     * commons-test-1.4-src.tar.gz should have it's name as the key.
     *
     * @param artifact the {@link Artifact} that we wish to generate a key for.
     * @return the generated key
     */
    private String getArtifactKey(final Artifact artifact) {
        return artifact.getFile().getName();
    }
}
