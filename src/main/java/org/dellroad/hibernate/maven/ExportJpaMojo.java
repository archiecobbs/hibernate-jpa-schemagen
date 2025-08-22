
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.api.metadata.MetadataDescriptor;
import org.hibernate.tool.hbm2x.Exporter;
import org.hibernate.tool.hbm2x.Hbm2DDLExporter;
import org.hibernate.tool.internal.metadata.JpaMetadataDescriptor;

/**
 * Mojo that writes the DDL schema to an output file based on JPA meta-data,
 * without requiring a database connection.
 *
 * @see <a href="https://github.com/archiecobbs/hibernate-jpa-schemagen">hibernate-jpa-schemagen</a>
 */
@Mojo(name = "export-jpa-schema",
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class ExportJpaMojo extends AbstractClasspathMojo {

    private static final String GENERATED_JPA_UNIT_NAME = "generated";

    /** JPA persistence unit name. */
    @Parameter(defaultValue = "")
    private String jpaUnit;

    /** Hibernate SQL dialect class name. */
    @Parameter(defaultValue = "")
    private String dialect;

    /** Root directory where compiled classes and {@code META-INF/persistence.xml} can be found. */
    @Parameter(defaultValue = "${project.build.directory}/classes")
    private File classRoot;

    /** Whether to remove {@code META-INF/persistence.xml} when done. */
    @Parameter(defaultValue = "")
    private String removePersistenceXml;

    /** Classpath resource for the {@code META-INF/persistence.xml} template. */
    @Parameter(defaultValue = "META-INF/hibernate-jpa-schemagen/persistence-template.xml")
    private String persistenceXmlTemplate;

    /** Optional properties file. Allows adding/overriding properties configured by the plugin. */
    @Parameter
    private File propertyFile;

    /** The output file for the generated schema.
     *
     * <p>
     * If this is set to empty string, a temporary file is used and then discarded.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-resources/schema.ddl")
    private String outputFile;

    private File actualOutputFile;

    /** A file to compare the generated DDL script against for unexpected changes.
     *
     * <p>
     * If the files don't match, or the file doesn't exist the build fails. This can be used to signal that
     * a schema migration is required (after which you can update the file).
     *
     * <p>
     * If this is set to empty string, no comparison is made.
     */
    @Parameter(defaultValue = "${project.basedir}/src/schema/schema.ddl")
    private String verifyFile;

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
     * {@link org.hibernate.cfg.AvailableSettings#GLOBALLY_QUOTED_IDENTIFIERS}
     */
    @Parameter(defaultValue = "false")
    private boolean globallyQuotedIdentifiers;

    /**
     * {@link org.hibernate.cfg.AvailableSettings#GLOBALLY_QUOTED_IDENTIFIERS_SKIP_COLUMN_DEFINITIONS}
     */
    @Parameter(defaultValue = "false")
    private boolean globallyQuotedIdentifiersSkipColumnDefinitions;

    /**
     * Match/replace "fixups" to apply to the generated schema.
     */
    @Parameter
    private List<Fixup> fixups = new ArrayList<>();

// AbstractClasspathMojo

    @Override
    @SuppressWarnings("unchecked")
    protected void addClasspathElements(Set<URL> elements) throws DependencyResolutionRequiredException {
        elements.add(this.toURL(this.classRoot));
        Stream.of(
            (List<String>)this.project.getCompileClasspathElements(),
            (List<String>)this.project.getRuntimeClasspathElements())
          .flatMap(List::stream)
          .map(File::new)
          .map(this::toURL)
          .forEach(elements::add);
    }

    @Override
    protected void executeWithClasspath() throws MojoExecutionException {

        // Determine execution mode
        final boolean hasJpaUnit = !this.nullOrEmpty(this.jpaUnit);
        final boolean hasDialect = !this.nullOrEmpty(this.dialect);
        if (hasJpaUnit != !hasDialect)
            throw new MojoExecutionException("Exactly one of <jpaUnit> or <dialect> must be configured");

        // Generate persistence.xml if needed
        final File metaInf = new File(this.classRoot, "META-INF");
        final File persistenceXml = new File(metaInf, "persistence.xml");
        final boolean removePersistenceXmlBool;
        if (hasDialect) {
            removePersistenceXmlBool = this.nullOrEmpty(this.removePersistenceXml) || Boolean.valueOf(this.removePersistenceXml);
            this.jpaUnit = GENERATED_JPA_UNIT_NAME;
            this.generatePersistenceXml(persistenceXml);
        } else
            removePersistenceXmlBool = !this.nullOrEmpty(this.removePersistenceXml) && Boolean.valueOf(this.removePersistenceXml);

        // Sanity check
        if (!persistenceXml.exists())
            throw new MojoExecutionException("No JPA persistence file found at location " + persistenceXml);

        // Replace an empty output file with temporary file
        final boolean discardOutput = this.nullOrEmptyOrNone(this.outputFile);
        if (discardOutput) {
            try {
                this.actualOutputFile = File.createTempFile(this.getClass().getSimpleName(), ".sql");
            } catch (IOException e) {
                throw new MojoExecutionException("Error creating temporary file", e);
            }
            this.actualOutputFile.deleteOnExit();
        } else
            this.actualOutputFile = new File(this.outputFile);

        // Get properties
        final Properties properties = this.readProperties();
        properties.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, this.globallyQuotedIdentifiers);
        properties.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS_SKIP_COLUMN_DEFINITIONS,
                this.globallyQuotedIdentifiersSkipColumnDefinitions);

        // Create MetadataDescriptor
        this.getLog().info("Gathering Hibernate meta-data");
        final MetadataDescriptor metadataDescriptor = this.createMetadataDescriptor(properties);

        // Create exporter
        final Exporter exporter = this.createExporter(properties);

        // Configure exporter
        this.configureExporter(exporter, properties, metadataDescriptor);

        // Delete the output file to ensure it actually gets (re)generated
        this.actualOutputFile.delete();

        // Run exporter
        this.getLog().info("Invoking Hibernate exporter");
        exporter.start();
        this.getLog().info(String.format("Wrote generated schema to %s", this.actualOutputFile));

        // Clean up
        if (removePersistenceXmlBool) {
            this.getLog().info(String.format("Removing %s %s", hasDialect ? "generated" : "user-supplied", persistenceXml));
            persistenceXml.delete();
            metaInf.delete();           // ok if this fails, that means directory is not empty
        }

        // Apply fixups
        this.applyFixups(properties);

        // Verify result
        this.verifyOutput();

        // Remove temporary file
        if (discardOutput)
            this.actualOutputFile.delete();
    }

    protected boolean nullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    protected boolean nullOrEmptyOrNone(String s) {
        return this.nullOrEmpty(s) || s.equals("NONE");
    }

// Subclass Hooks

    protected void generatePersistenceXml(File persistenceXml) throws MojoExecutionException {
        this.getLog().info(String.format("Generating %s", persistenceXml));
        persistenceXml.getParentFile().mkdirs();
        try (
          Reader in = new InputStreamReader(Thread.currentThread().getContextClassLoader().getResourceAsStream(
            this.persistenceXmlTemplate), StandardCharsets.UTF_8);
          Writer out = new OutputStreamWriter(new FileOutputStream(persistenceXml), StandardCharsets.UTF_8)) {
            final StringWriter buf = new StringWriter();
            final char[] chunk = new char[256];
            int r;
            while ((r = in.read(chunk)) != -1)
                buf.write(chunk, 0, r);
            out.write(buf.toString()
              .replaceAll("@jpaName@", this.jpaUnit)
              .replaceAll("@dialect@", this.dialect));
        } catch (IOException e) {
            throw new MojoExecutionException(String.format(
              "Error generating %s from template %s", persistenceXml, this.persistenceXmlTemplate), e);
        }
    }

    protected MetadataDescriptor createMetadataDescriptor(Properties properties) {
        return new JpaMetadataDescriptor(this.jpaUnit, properties);
    }

    protected Exporter createExporter(Properties properties) {
        final Hbm2DDLExporter exporter = new Hbm2DDLExporter();
        exporter.getProperties().putAll(properties);
        return exporter;
    }

    protected void configureExporter(Exporter exporter0, Properties properties, MetadataDescriptor metadataDescriptor) {
        final Hbm2DDLExporter exporter = (Hbm2DDLExporter)exporter0;
        exporter.getProperties().putAll(properties);
        exporter.setOutputDirectory(Optional.ofNullable(this.actualOutputFile.getParentFile()).orElseGet(() -> new File(".")));
        exporter.setMetadataDescriptor(metadataDescriptor);
        exporter.setTemplatePath(new String[0]);
        exporter.setExport(false);
        exporter.setConsole(false);
        exporter.setUpdate(false);
        exporter.setDelimiter(this.delimiter);
        exporter.setDrop(this.drop);
        exporter.setCreate(true);
        exporter.setFormat(this.format);
        exporter.setOutputFileName(this.actualOutputFile.getName());
        exporter.setHaltonerror(true);
    }

    protected Properties readProperties() throws MojoExecutionException {
        final Properties properties = new Properties();
        if (this.propertyFile != null) {
            this.getLog().debug(String.format("Loading schema generation properties from %s", this.propertyFile));
            try (FileInputStream input = new FileInputStream(this.propertyFile)) {
                properties.load(input);
            } catch (IOException e) {
                throw new MojoExecutionException(String.format("Error loading %s: %s", this.propertyFile, e.getMessage()), e);
            }
        }
        return properties;
    }

    protected void applyFixups(Properties properties) throws MojoExecutionException {
        if (this.fixups.isEmpty())
            return;
        this.getLog().info(String.format("Applying %d fixup(s) to %s", this.fixups.size(), this.actualOutputFile));
        try {
            final String charset = Optional.ofNullable(properties.getProperty(AvailableSettings.HBM2DDL_CHARSET_NAME))
              .orElse("utf-8");
            String ddl = new String(Files.readAllBytes(this.actualOutputFile.toPath()), charset);
            boolean completedAllFixups = false;
            try {
                int index = 1;
                for (Fixup fixup : this.fixups) {
                    if (this.getLog().isDebugEnabled()) {
                        this.getLog().debug(String.format("Applying fixup #%d to generated schema...", index));
                        this.getLog().debug(String.format("  pattern: \"%s\"", fixup.pattern));
                        this.getLog().debug(String.format("  replace: \"%s\"", Optional.ofNullable(fixup.replacement).orElse("")));
                        this.getLog().debug(String.format(" required: %s", fixup.modificationRequired));
                    }
                    final String ddl2;
                    try {
                        ddl2 = fixup.applyTo(index, ddl);
                    } catch (IllegalArgumentException e) {
                        throw new MojoExecutionException(String.format("Error applying fixup #%d: %s", index, e.getMessage()), e);
                    }
                    if (!ddl2.equals(ddl)) {
                        if (this.getLog().isDebugEnabled()) {
                            this.getLog().debug(String.format(
                              "Fixup #%d resulted in schema modification (old length %d, new length %d)",
                              index, ddl.length(), ddl2.length()));
                        }
                        ddl = ddl2;
                    } else {
                        if (this.getLog().isDebugEnabled())
                            this.getLog().debug(String.format("Fixup #%d resulted in no schema modification", index));
                        if (fixup.modificationRequired) {
                            throw new MojoExecutionException(String.format(
                              "Fixup #%d is required to modify the schema but no modification occurred", index));
                        }
                    }
                    index++;
                }
                completedAllFixups = true;
            } finally {     // even if a fixup fails, leave the output file as it was just prior to that fixup
                try {
                    Files.write(this.actualOutputFile.toPath(), ddl.getBytes(charset));
                } catch (IOException e) {
                    if (completedAllFixups)
                        throw e;
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException(String.format(
              "Error applying schema fixups to %s: %s", this.actualOutputFile, e.getMessage()), e);
        }
    }

    protected void verifyOutput() throws MojoExecutionException {
        if (this.nullOrEmptyOrNone(this.verifyFile)) {
            this.getLog().info("Not verifying generated schema (no verification file configured)");
            return;
        }
        final File verifile = new File(this.verifyFile);
        if (!verifile.exists()) {
            throw new MojoExecutionException(String.format(
              "Error verifying schema output: verification file %s does not exist", verifile));
        }
        this.getLog().info(String.format("Comparing generated schema to %s", verifile));
        try {
            final byte[] actual = Files.readAllBytes(this.actualOutputFile.toPath());
            final byte[] expected = Files.readAllBytes(verifile.toPath());
            if (!Arrays.equals(actual, expected))
                throw new MojoExecutionException("Generated schema differs from expected schema (schema migration may be needed)");
        } catch (IOException e) {
            throw new MojoExecutionException(String.format(
              "Error verifying schema output against %s: %s", verifile, e.getMessage()), e);
        }
        this.getLog().info("Schema verification succeeded");
    }
}
