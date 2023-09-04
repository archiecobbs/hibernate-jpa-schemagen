
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Comparator;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Support superclass for mojo's that need to put stuff (depenedencies, etc.) on the classpath.
 */
public abstract class AbstractClasspathMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    /**
     * Add whatever is needed to the classpath that should be visible during execution.
     */
    protected abstract void addClasspathElements(Set<URL> elements) throws DependencyResolutionRequiredException;

// AbstractMojo

    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {

        // Get classpath elements
        final LinkedHashSet<URL> urls = new LinkedHashSet<>();
        try {
            this.addClasspathElements(urls);
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("error adding classpath elements: " + e.getMessage(), e);
        }

        // Debug
        this.getLog().debug(this.getClass().getSimpleName() + ": classpath for execution:");
        urls.forEach(url -> this.getLog().debug("  " + url));

        // Create corresponding class loader and execute
        final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        final URLClassLoader classpathLoader = URLClassLoader.newInstance(urls.toArray(new URL[urls.size()]), contextLoader);
        this.runWithLoader(classpathLoader, this::executeWithClasspath);
    }

// Subclass Hooks

    protected abstract void executeWithClasspath() throws MojoExecutionException, MojoFailureException;

// Internal Methods

    /**
     * Execute the given action while the specified {@link ClassLoader} is the current thread's context loader.
     */
    protected void runWithLoader(ClassLoader loader, MojoRunnable action) throws MojoExecutionException, MojoFailureException {
        final ClassLoader origLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(origLoader);
        }
    }

    /**
     * Convert a {@link File} into an {@link URL}.
     */
    protected URL toURL(File file) {
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("unexpected error", e);
        }
    }

// MojoRunnable

    @FunctionalInterface
    public interface MojoRunnable {
        void run() throws MojoExecutionException, MojoFailureException;
    }
}
