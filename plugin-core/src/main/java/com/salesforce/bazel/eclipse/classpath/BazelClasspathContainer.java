/**
 * Copyright (c) 2019, Salesforce.com, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.salesforce.bazel.eclipse.classpath;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathContainer;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaModelException;
import org.osgi.service.prefs.BackingStoreException;

import com.salesforce.bazel.eclipse.BazelPluginActivator;
import com.salesforce.bazel.eclipse.runtime.api.JavaCoreHelper;
import com.salesforce.bazel.eclipse.runtime.api.ResourceHelper;
import com.salesforce.bazel.eclipse.runtime.impl.EclipseWorkProgressMonitor;
import com.salesforce.bazel.sdk.command.BazelCommandLineToolConfigurationException;
import com.salesforce.bazel.sdk.lang.jvm.BazelJvmClasspath;
import com.salesforce.bazel.sdk.lang.jvm.JvmClasspathEntry;
import com.salesforce.bazel.sdk.logging.LogHelper;
import com.salesforce.bazel.sdk.model.BazelWorkspace;
import com.salesforce.bazel.sdk.project.BazelProject;
import com.salesforce.bazel.sdk.project.BazelProjectManager;
import com.salesforce.bazel.sdk.workspace.OperatingEnvironmentDetectionStrategy;

/**
 * Computes the classpath for a Bazel package and provides it to the JDT tooling in Eclipse.
 */
public class BazelClasspathContainer implements IClasspathContainer {
    public static final String CONTAINER_NAME = "com.salesforce.bazel.eclipse.BAZEL_CONTAINER";
    
    private final BazelWorkspace bazelWorkspace;
    private final BazelProjectManager bazelProjectManager;
    private BazelProject bazelProject;
    private final IPath eclipseProjectPath;
    private final boolean eclipseProjectIsRoot;
    private final BazelJvmClasspath bazelClasspath;
    private final JavaCoreHelper javaCoreHelper;
    private final OperatingEnvironmentDetectionStrategy osDetector;
    private final LogHelper logger;

    
    private static List<BazelJvmClasspath> instances = new ArrayList<>();
    
    public BazelClasspathContainer(IProject eclipseProject)
            throws IOException, InterruptedException, BackingStoreException, JavaModelException,
            BazelCommandLineToolConfigurationException {
        this(eclipseProject, BazelPluginActivator.getResourceHelper());
    }
    
    BazelClasspathContainer(IProject eclipseProject, ResourceHelper resourceHelper)
            throws IOException, InterruptedException, BackingStoreException, JavaModelException,
            BazelCommandLineToolConfigurationException {
    	this.bazelWorkspace = BazelPluginActivator.getBazelWorkspace();
    	this.bazelProjectManager = BazelPluginActivator.getBazelProjectManager();
        this.eclipseProjectPath = eclipseProject.getLocation();
        this.eclipseProjectIsRoot = resourceHelper.isBazelRootProject(eclipseProject);
        this.osDetector = BazelPluginActivator.getInstance().getOperatingEnvironmentDetectionStrategy();
        
        this.bazelProject = this.bazelProjectManager.getProject(eclipseProject.getName());
        if (this.bazelProject == null) {
        	this.bazelProject = new BazelProject(eclipseProject.getName(), eclipseProject);
        	this.bazelProjectManager.addProject(bazelProject);
        }
        
        bazelClasspath = new BazelJvmClasspath(this.bazelWorkspace, 
        		bazelProjectManager,
        		bazelProject, 
        		new EclipseImplicitClasspathHelper(), 
        		osDetector, 
        		BazelPluginActivator.getBazelCommandManager());
        instances.add(bazelClasspath);
        
        javaCoreHelper = BazelPluginActivator.getJavaCoreHelper();
		logger = LogHelper.log(this.getClass());

    }
    
    public static void clean() {
        for (BazelJvmClasspath instance : instances) {
        	instance.clean();
        }
    }

    @Override
    public IClasspathEntry[] getClasspathEntries() {
        if (this.eclipseProjectIsRoot) {
            // this project is the artificial container to hold Bazel workspace scoped assets (e.g. the WORKSPACE file)
            return new IClasspathEntry[] {};
        }

        /**
         * Observed behavior of Eclipse is that this method can get called multiple times before the first invocation completes, therefore 
         * the cache is not as effective as it could be. Synchronize on this instance such that the first invocation completes and populates
         * the cache before the subsequent calls are allowed to proceed.
         */
        synchronized (this) {

		    // the Java SDK will produce a list of logical classpath entries 
			JvmClasspathEntry[] jvmClasspathEntries = bazelClasspath.getClasspathEntries(new EclipseWorkProgressMonitor(null));
			
			// convert the logical entries into concrete Eclipse entries
			List<IClasspathEntry> eclipseClasspathEntries = new ArrayList<>();
		    File bazelOutputBase = bazelWorkspace.getBazelOutputBaseDirectory();
		    File bazelExecRoot = bazelWorkspace.getBazelExecRootDirectory();
			
			for (JvmClasspathEntry entry : jvmClasspathEntries) {
				if (entry.pathToJar != null) {
		            IPath jarPath = getIPathOnDisk(bazelOutputBase, bazelExecRoot, entry.pathToJar);
		            if (jarPath != null) {
		                IPath srcJarPath = getIPathOnDisk(bazelOutputBase, bazelExecRoot, entry.pathToSourceJar);
		                IPath srcJarRootPath = null;
		        		eclipseClasspathEntries.add(javaCoreHelper.newLibraryEntry(jarPath, srcJarPath, 
		        				srcJarRootPath, entry.isTestJar));
		            }
				} else {
					IProject iproject = (IProject)entry.bazelProject.projectImpl;
					IPath ipath = iproject.getFullPath();
					eclipseClasspathEntries.add(javaCoreHelper.newProjectEntry(ipath));
				}
			}
		    return eclipseClasspathEntries.toArray(new IClasspathEntry[] {});
        }
    }

    @Override
    public String getDescription() {
        return "Bazel Classpath Container";
    }

    @Override
    public int getKind() {
        return K_APPLICATION;
    }

    @Override
    public IPath getPath() {
        return eclipseProjectPath;
    }

    private IPath getIPathOnDisk(File bazelOutputBase, File bazelExecRoot, String file) {
        if (file == null) {
            return null;
        }
        Path path = null;
        if (file.startsWith("external")) {
            path = Paths.get(bazelOutputBase.toString(), file);
        } else {
            path = Paths.get(bazelExecRoot.toString(), file);
        }

        // We have had issues with Eclipse complaining about symlinks in the Bazel output directories not being real,
        // so we resolve them before handing them back to Eclipse.
        if (Files.isSymbolicLink(path)) {
            try {
                // resolving the link will fail if the symlink does not a point to a real file
                path = Files.readSymbolicLink(path);
            } catch (IOException ex) {
                // TODO this can happen if someone does a 'bazel clean' using the command line #113
                // https://github.com/salesforce/bazel-eclipse/issues/113
            	logger.error("Problem adding jar to project ["+bazelProject.name+"] because it does not exist on the filesystem: "+path);
                continueOrThrow(ex);
            }
        } else {
            // it is a normal path, check for existence
            if (!Files.exists(path)) {
                // TODO this can happen if someone does a 'bazel clean' using the command line #113
                // https://github.com/salesforce/bazel-eclipse/issues/113
            	logger.error("Problem adding jar to project ["+bazelProject.name+"] because it does not exist on the filesystem: "+path);
            }
        }
        return org.eclipse.core.runtime.Path.fromOSString(path.toString());
    }

    private void continueOrThrow(Throwable th) {
        // under real usage, we suppress fatal exceptions because sometimes there are IDE timing issues that can
        // be corrected if the classpath is computed again.
        // But under tests, we want to fail fatally otherwise tests could pass when they shouldn't
        if (osDetector.isTestRuntime()) {
            throw new IllegalStateException("The classpath could not be computed by the BazelClasspathContainer", th);
        }
    }

}
