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

import edu.emory.mathcs.backport.java.util.Collections;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.AttachedArtifact;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The purpose of this maven mojo is to detach the artifacts generated by the maven-assembly-plugin,
 * which for the Apache Commons Project do not get uploaded to Nexus, and putting those artifacts
 * in the dev distribution location for apache projects.
 *
 * @author chtompki
 * @since 1.0
 */
@Mojo( name = "test", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CommonsAssemblyStagingMojo extends AbstractMojo {

    /**
     * A list of "artifact types" in the maven vernacular, to
     * be detatched from the deployment. For the time being we want
     * all artifacts generated by the maven-assembly-plugin to be detatched
     * from the deployment, namely *-src.zip, *-src.tar.gz, *-bin.zip,
     * *-bin.tar.gz, and the corresponding .asc pgp signatures.
     */
    private static final Set<String> ARTIFACT_TYPES_TO_DETATCH;
    static {
        Set<String> hashSet = new HashSet<>();
        hashSet.add("zip");
        hashSet.add("tar.gz");
        hashSet.add("zip.asc");
        hashSet.add("tar.gz.asc");
        ARTIFACT_TYPES_TO_DETATCH = Collections.unmodifiableSet(hashSet);
    }

    /**
     * This list is supposed to hold the maven references to the aformentioned artifacts so that we
     * can upload them to svn after they've been detatched from the maven deployment.
     */
    private List<AttachedArtifact> detatchedArtifacts = new ArrayList<>();

    /**
     * The maven project context injection so that we can get a hold of the variables at hand.
     */
    @Parameter( defaultValue = "${project}", required = true )
    private MavenProject project;

    @Parameter( defaultValue = "${project.build.directory}/commons-release-plugin", alias = "outputDirectory" )
    private File workingDirectory;

    @Parameter ( required = true )
    private String pubScmStagingUrl;

    public void execute() {
        getLog().info("Detatching Assemblies");
        for (Object attachedArtifact : project.getAttachedArtifacts()) {
            if (ARTIFACT_TYPES_TO_DETATCH.contains(((AttachedArtifact) attachedArtifact).getType())) {
                detatchedArtifacts.add((AttachedArtifact) attachedArtifact);
            }
        }
        for(AttachedArtifact artifactToRemove : detatchedArtifacts) {
            project.getAttachedArtifacts().remove(artifactToRemove);
        }

    }
}