
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.tools.ant.BuildException;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.api.export.Exporter;
import org.hibernate.tool.api.export.ExporterConstants;
import org.hibernate.tool.api.export.ExporterFactory;
import org.hibernate.tool.api.export.ExporterType;
import org.hibernate.tool.api.metadata.MetadataDescriptor;
import org.hibernate.tool.internal.metadata.JpaMetadataDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Mojo that writes the DDL schema to an output file based on JPA meta-data,
 * without requiring a database connection.
 *
 * <p>
 * A {@code META-INF/persistence.xml} file is still required, even if this file is not used at runtime,
 * and it needs to exist under the same root as your compiled JPA classes (usually {@code target/classes}).
 * This file should at least include the {@code hibernate.dialect} property; it does <b>not</b>
 * need to specify a JDBC connection (although you can if you want). For example:
 *  <blockquote><code>
 *  &lt;?xml version="1.0" encoding="UTF-8"?&gt;
 *  &lt;persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence"&gt;
 *      &lt;persistence-unit name="myjpa"&gt;
 *          &lt;properties&gt;
 *              &lt;property name="hibernate.dialect" value="org.hibernate.dialect.MySQLDialect"/&gt;
 *          &lt;/properties&gt;
 *      &lt;/persistence-unit&gt;
 *  &lt;/persistence&gt;
 *  </code></blockquote>
 * If you don't need {@code META-INF/persistence.xml} at runtime, set {@code removePersistenceXml} to true.
 *
 * <p>
 * Mojo also includes support for regex-based fixups and verification against an expected output.
 *
 * <p>
 * Note: you can safely ignore any "The application must supply JDBC connections" exceptions.
 */
@Mojo(name = "export-jpa-schema", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class ExportJpaMojo extends AbstractMojo {

    /** JPA persistence unit name. */
    @Parameter
    private String jpaUnit;

    /** Root directory where compiled classes and {@code META-INF/persistence.xml} can be found. */
    @Parameter(defaultValue = "${project.build.directory}/classes")
    private File classRoot;

    /** Whether to remove {@code META-INF/persistence.xml} when done. */
    @Parameter(defaultValue = "false")
    private boolean removePersistenceXml;

    /** Optional properties file. Allows adding/overriding properties configured by the plugin. */
    @Parameter
    private File propertyFile;

    /** The output file for the generated schema. */
    @Parameter(defaultValue = "${project.build.directory}/generated-resources/schema.ddl")
    private File outputFile;

    /** A file to compare the generated DDL script against for unexpected changes.
     *
     * <p>
     * If the files don't match, the build fails. This can be used to signal that
     * a schema migration is required (after which you can update the file).
     *
     * <p>
     * If this file doesn't exist, no comparison is made.
     */
    @Parameter(defaultValue = "${project.basedir}/src/schema/schema.ddl")
    private File verifyFile;

    /**
     * Whether to include {@code DROP TABLE} statements.
     */
    @Parameter(defaultValue = "false")
    private boolean drop;

    /**
     * The delimiter that separates statements.
     */
    @Parameter(defaultValue = ";")
    private String delimiter;

    /**
     * Whether to format SQL strings.
     */
    @Parameter(defaultValue = "true")
    private boolean format;

    /**
     * Match/replace "fixups" to apply to the generated schema.
     */
    @Parameter
    private List<Fixup> fixups = new ArrayList<>();

    @Override
    public void execute() throws MojoExecutionException {

        // Sanity check
        final File metaInf = new File(this.classRoot, "META-INF");
        final File persistenceXml = new File(metaInf, "persistence.xml");
        if (!persistenceXml.exists())
            throw new MojoExecutionException("No JPA persistence file found at location " + persistenceXml);

        // Get properties
        final Properties properties = this.readProperties();

        // Run the rest with a temporary loader
        this.runWithLoader(() -> {

            // Create MetadataDescriptor
            final MetadataDescriptor metadataDescriptor = new JpaMetadataDescriptor(this.jpaUnit, properties);

            // Create exporter
            final Exporter exporter = this.createExporter(properties);

            // Configure exporter
            this.configureExporter(exporter, properties, metadataDescriptor);

            // Run exporter
            exporter.start();
        });
        this.getLog().info("Wrote generated schema to " + this.outputFile);

        // Clean up
        if (this.removePersistenceXml) {
            this.getLog().info("Removing " + persistenceXml);
            persistenceXml.delete();
            metaInf.delete();           // ok if this fails, that means directory is not empty
        }

        // Apply fixups
        this.applyFixups(properties);

        // Verify result
        this.verifyOutput();
    }

    protected void runWithLoader(Runnable action) {

        // Create a new loader that has visibility into <classRoot>
        final ClassLoader origLoader = Thread.currentThread().getContextClassLoader();
        final URLClassLoader tempLoader = URLClassLoader.newInstance(new URL[] { this.toURL(this.classRoot) }, origLoader);

        // Temporarily make this loader the current loader while we perform the action
        Thread.currentThread().setContextClassLoader(tempLoader);
        try {
            action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(origLoader);
        }
    }

    protected URL toURL(File file) {
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException("unexpected error", e);
        }
    }

// Subclass Hooks

    protected Exporter createExporter(Properties properties) {
        final Exporter exporter = ExporterFactory.createExporter(ExporterType.DDL);
        exporter.getProperties().putAll(properties);
        return exporter;
    }

    protected void configureExporter(Exporter exporter, Properties properties, MetadataDescriptor metadataDescriptor) {
        exporter.getProperties().putAll(properties);
        exporter.getProperties().put(ExporterConstants.DESTINATION_FOLDER,
          Optional.ofNullable(this.outputFile.getParentFile()).orElseGet(() -> new File(".")));
        exporter.getProperties().put(ExporterConstants.METADATA_DESCRIPTOR, metadataDescriptor);
        exporter.getProperties().put(ExporterConstants.TEMPLATE_PATH, new String[0]);
        exporter.getProperties().put(ExporterConstants.EXPORT_TO_DATABASE, false);
        exporter.getProperties().put(ExporterConstants.EXPORT_TO_CONSOLE, false);
        exporter.getProperties().put(ExporterConstants.SCHEMA_UPDATE, false);
        exporter.getProperties().put(ExporterConstants.DELIMITER, this.delimiter);
        exporter.getProperties().put(ExporterConstants.DROP_DATABASE, this.drop);
        exporter.getProperties().put(ExporterConstants.CREATE_DATABASE, true);
        exporter.getProperties().put(ExporterConstants.FORMAT, this.format);
        exporter.getProperties().put(ExporterConstants.OUTPUT_FILE_NAME, this.outputFile.getName());
        exporter.getProperties().put(ExporterConstants.HALT_ON_ERROR, true);
    }

    protected Properties readProperties() throws MojoExecutionException {
        final Properties properties = new Properties();
        if (this.propertyFile != null) {
            this.getLog().debug("Loading schema generation properties from " + this.propertyFile);
            try (FileInputStream input = new FileInputStream(this.propertyFile)) {
                properties.load(input);
            } catch (IOException e) {
                throw new MojoExecutionException("Error loading " + this.propertyFile + ": " + e.getMessage(), e);
            }
        }
        return properties;
    }

    protected void applyFixups(Properties properties) throws MojoExecutionException {
        this.getLog().info("Applying " + this.fixups.size() + " fixup(s) to " + this.outputFile);
        try {
            final String charset = Optional.ofNullable(properties.getProperty(AvailableSettings.HBM2DDL_CHARSET_NAME))
              .orElse("utf-8");
            String ddl = new String(Files.readAllBytes(this.outputFile.toPath()), charset);
            int index = 1;
            for (Fixup fixup : this.fixups) {
                try {
                    this.getLog().debug("Applying fixup #" + index + " to generated schema");
                    ddl = fixup.applyTo(ddl);
                } catch (IllegalArgumentException e) {
                    throw new MojoExecutionException("Error applying fixup #" + index + ": " + e.getMessage(), e);
                }
                index++;
            }
            Files.write(this.outputFile.toPath(), ddl.getBytes(charset));
        } catch (IOException e) {
            throw new MojoExecutionException("Error applying fixups to " + this.outputFile + ": " + e.getMessage(), e);
        }
    }

    protected void verifyOutput() throws MojoExecutionException {
        if (!this.verifyFile.exists()) {
            this.getLog().info("Not verifying generated schema; " + this.verifyFile + " does not exist");
            return;
        }
        this.getLog().info("Comparing generated schema to " + this.verifyFile);
        try {
            final byte[] actual = Files.readAllBytes(this.outputFile.toPath());
            final byte[] expected = Files.readAllBytes(this.verifyFile.toPath());
            if (!Arrays.equals(actual, expected))
                throw new MojoExecutionException("Generated schema differs from expected schema (schema migration may be needed)");
        } catch (IOException e) {
            throw new MojoExecutionException("Error verifying output against " + this.verifyFile + ": " + e.getMessage(), e);
        }
        this.getLog().info("Schema verification succeeded");
    }
}
