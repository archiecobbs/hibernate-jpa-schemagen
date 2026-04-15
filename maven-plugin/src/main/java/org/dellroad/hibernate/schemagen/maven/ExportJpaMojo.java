
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.dellroad.hibernate.schemagen.core.Log;
import org.dellroad.hibernate.schemagen.core.MatchReplace;
import org.dellroad.hibernate.schemagen.core.SchemaGenerator;

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
     * {@link org.hibernate.cfg.MappingSettings#GLOBALLY_QUOTED_IDENTIFIERS}
     */
    @Parameter(defaultValue = "false")
    private boolean globallyQuotedIdentifiers;

    /**
     * {@link org.hibernate.cfg.MappingSettings#GLOBALLY_QUOTED_IDENTIFIERS_SKIP_COLUMN_DEFINITIONS}
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

        // Create and configure schema generator
        final SchemaGenerator generator = this.buildSchemaGenerator();

        // Execute generator
        try {
            generator.generate();
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("Schema generation error: " + e.getMessage(), e);
        }
    }

// Subclass Hooks

    protected SchemaGenerator buildSchemaGenerator() throws MojoExecutionException {

        // Do some parsing
        final String actualJpaUnit = this.nullIfEmpty(this.jpaUnit);
        final String actualDialect = this.nullIfEmpty(this.dialect);
        final boolean actualRemovePersistenceXml = Optional.ofNullable(this.removePersistenceXml)
          .map(Boolean::valueOf)
          .orElse(actualDialect != null);

        // Create and configure schema generator
        final SchemaGenerator generator = new SchemaGenerator();
        generator.setLog(this.buildLog());
        generator.setJpaUnit(actualJpaUnit);
        generator.setDialect(actualDialect);
        generator.setClassRoot(this.classRoot);
        generator.setRemovePersistenceXml(actualRemovePersistenceXml);
        generator.setPersistenceXmlTemplate(this.readPersistenceXmlTemplateIfNeeded());
        generator.setPropertyFile(this.propertyFile);
        generator.setOutputFile(Optional.ofNullable(this.nullIfEmptyOrNone(this.outputFile)).map(File::new).orElse(null));
        generator.setVerifyFile(Optional.ofNullable(this.nullIfEmptyOrNone(this.verifyFile)).map(File::new).orElse(null));
        generator.setDrop(this.drop);
        generator.setDelimiter(this.nullIfEmpty(this.delimiter));
        generator.setFormat(this.format);
        generator.setGloballyQuotedIdentifiers(this.globallyQuotedIdentifiers);
        generator.setGloballyQuotedIdentifiersSkipColumnDefinitions(this.globallyQuotedIdentifiersSkipColumnDefinitions);
        generator.setFixups(this.toMatchReplaceList(this.fixups));
        return generator;
    }

    protected Log buildLog() throws MojoExecutionException {
        return new Log() {
            @Override
            public boolean isDebugEnabled() {
                return ExportJpaMojo.this.getLog().isDebugEnabled();
            }
            @Override
            public void debug(String message) {
                ExportJpaMojo.this.getLog().debug(message);
            }
            @Override
            public void info(String message) {
                ExportJpaMojo.this.getLog().info(message);
            }
        };
    };

    protected String readPersistenceXmlTemplateIfNeeded() throws MojoExecutionException {
        final boolean hasDialect = this.nullIfEmpty(this.dialect) != null;
        if (!hasDialect)
            return null;
        try (Reader in = new InputStreamReader(
          Thread.currentThread().getContextClassLoader().getResourceAsStream(
           this.persistenceXmlTemplate), StandardCharsets.UTF_8)) {
            final StringWriter buf = new StringWriter();
            final char[] chunk = new char[256];
            int r;
            while ((r = in.read(chunk)) != -1)
                buf.write(chunk, 0, r);
            return buf.toString();
        } catch (IOException e) {
            throw new MojoExecutionException(String.format(
              "Error reading persistence.xml template %s: %s", this.persistenceXmlTemplate, e.getMessage()), e);
        }
    }

// Utility methods

    protected String nullIfEmpty(String s) {
        return s != null && !s.isEmpty() ? s : null;
    }

    protected String nullIfEmptyOrNone(String s) {
        return s != null && !s.isEmpty() && !s.equals("NONE") ? s : null;
    }

    protected List<MatchReplace> toMatchReplaceList(List<Fixup> fixups) throws MojoExecutionException {
        final ArrayList<MatchReplace> list = new ArrayList<>();
        for (Fixup fixup : fixups) {
            final Pattern pattern;
            try {
                pattern = Pattern.compile(fixup.pattern);
            } catch (IllegalArgumentException e) {
                throw new MojoExecutionException(String.format(
                  "Invalid regular expression \"%s\" in fixup: %s", fixup.pattern, e.getMessage()), e);
            }
            final String replacement = Optional.ofNullable(fixup.replacement).orElse("");
            list.add(new MatchReplace(pattern, replacement, fixup.modificationRequired));
        }
        return list;
    }
}
