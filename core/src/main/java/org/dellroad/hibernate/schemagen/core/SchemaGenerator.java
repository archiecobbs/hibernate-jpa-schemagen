
/*
 * Copyright (C) 2023 Archie L. Cobbs. All rights reserved.
 */

package org.dellroad.hibernate.schemagen.core;

import com.google.common.base.Preconditions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.tool.api.export.Exporter;
import org.hibernate.tool.api.export.ExporterConstants;
import org.hibernate.tool.api.export.ExporterFactory;
import org.hibernate.tool.api.export.ExporterType;
import org.hibernate.tool.api.metadata.MetadataDescriptor;
import org.hibernate.tool.internal.metadata.JpaMetadataDescriptor;

/**
 * Writes the DDL schema to an output file based on JPA meta-data without requiring a database connection.
 *
 * @see <a href="https://github.com/archiecobbs/hibernate-jpa-schemagen">hibernate-jpa-schemagen</a>
 */
public class SchemaGenerator {

    private static final String GENERATED_JPA_UNIT_NAME = "generated";

    private Log log;
    private String jpaUnit;
    private String dialect;
    private File classRoot;
    private boolean removePersistenceXml;
    private String persistenceXmlTemplate;
    private File propertyFile;
    private File outputFile;
    private File verifyFile;
    private boolean drop;
    private String delimiter;
    private boolean format;
    private boolean globallyQuotedIdentifiers;
    private boolean globallyQuotedIdentifiersSkipColumnDefinitions;
    private List<MatchReplace> fixups;

// Configuration

    public void setLog(final Log log) {
        this.log = log;
    }
    public void setJpaUnit(final String jpaUnit) {
        this.jpaUnit = jpaUnit;
    }
    public void setDialect(final String dialect) {
        this.dialect = dialect;
    }
    public void setClassRoot(final File classRoot) {
        this.classRoot = classRoot;
    }
    public void setRemovePersistenceXml(final boolean removePersistenceXml) {
        this.removePersistenceXml = removePersistenceXml;
    }
    public void setPersistenceXmlTemplate(final String persistenceXmlTemplate) {
        this.persistenceXmlTemplate = persistenceXmlTemplate;
    }
    public void setPropertyFile(final File propertyFile) {
        this.propertyFile = propertyFile;
    }
    public void setOutputFile(final File outputFile) {
        this.outputFile = outputFile;
    }
    public void setVerifyFile(final File verifyFile) {
        this.verifyFile = verifyFile;
    }
    public void setDrop(final boolean drop) {
        this.drop = drop;
    }
    public void setDelimiter(final String delimiter) {
        this.delimiter = delimiter;
    }
    public void setFormat(final boolean format) {
        this.format = format;
    }
    public void setGloballyQuotedIdentifiers(final boolean globallyQuotedIdentifiers) {
        this.globallyQuotedIdentifiers = globallyQuotedIdentifiers;
    }
    public void setGloballyQuotedIdentifiersSkipColumnDefinitions(final boolean globallyQuotedIdentifiersSkipColumnDefinitions) {
        this.globallyQuotedIdentifiersSkipColumnDefinitions = globallyQuotedIdentifiersSkipColumnDefinitions;
    }
    public void setFixups(final List<MatchReplace> fixups) {
        this.fixups = fixups;
    }

// Generation

    /**
     * Generate schema.
     *
     * @throws IllegalArgumentException if this instance is misconfigured
     * @throws IOException if an I/O error occurs
     * @throws RuntimeException if any other error occurs
     */
    public void generate() throws IOException {

        // Debug
        if (this.log.isDebugEnabled()) {
            this.log.debug("SchemaGenerator configuration:");
            this.log.debug(String.format("    %22s = \"%s\"", "jpaUnit", this.jpaUnit));
            this.log.debug(String.format("    %22s = \"%s\"", "dialect", this.dialect));
            this.log.debug(String.format("    %22s = %s", "classRoot", this.classRoot));
            this.log.debug(String.format("    %22s = %s", "removePersistenceXml", this.removePersistenceXml));
            this.log.debug(String.format("    %22s = %s", "persistenceXmlTemplate",
              this.persistenceXmlTemplate == null ? "null" :
              "\n\n" + this.persistenceXmlTemplate.trim().replaceAll("(?s)^", "        ")));
            this.log.debug(String.format("    %22s = %s", "propertyFile", this.propertyFile));
            this.log.debug(String.format("    %22s = %s", "outputFile", this.outputFile));
            this.log.debug(String.format("    %22s = %s", "verifyFile", this.verifyFile));
            this.log.debug(String.format("    %22s = %s", "drop", this.drop));
            this.log.debug(String.format("    %22s = \"%s\"", "delimiter", this.delimiter));
            this.log.debug(String.format("    %22s = %s", "format", this.format));
            this.log.debug(String.format("    %22s = %s", "GQI", this.globallyQuotedIdentifiers));
            this.log.debug(String.format("    %22s = %s", "GQISCD", this.globallyQuotedIdentifiersSkipColumnDefinitions));
            this.log.debug(String.format("    %22s: (%d total)", "fixups", this.fixups.size()));
            for (MatchReplace fixup : this.fixups) {
                this.log.debug(String.format("    %22s", "{"));
                this.log.debug(String.format("    %26s = \"%s\"", "pattern", fixup.getPattern()));
                this.log.debug(String.format("    %26s = \"%s\"", "replacement", fixup.getReplacement()));
                this.log.debug(String.format("    %26s = %s", "modificationRequired", fixup.isModificationRequired()));
                this.log.debug(String.format("    %22s", "}"));
            }
        }

        // Sanity check
        Preconditions.checkArgument(this.log != null, "null log");
        Preconditions.checkArgument(this.classRoot != null, "null classRoot");
        Preconditions.checkArgument(this.jpaUnit == null || !this.jpaUnit.isEmpty(), "empty jpaUnit");
        Preconditions.checkArgument(this.dialect == null || !this.dialect.isEmpty(), "empty dialect");
        Preconditions.checkArgument((this.jpaUnit == null) ^ (this.dialect == null),
          "exactly one of <jpaUnit> or <dialect> must be configured");
        Preconditions.checkArgument(this.delimiter != null, "null delimiter");

        // Generate persistence.xml if needed
        final File metaInf = new File(this.classRoot, "META-INF");
        final File persistenceXml = new File(metaInf, "persistence.xml");
        if (this.dialect != null) {
            this.jpaUnit = GENERATED_JPA_UNIT_NAME;
            this.generatePersistenceXml(persistenceXml);
        }

        // Sanity check
        if (!persistenceXml.exists())
            throw new RuntimeException("no JPA persistence file found at location " + persistenceXml);

        // Replace an empty output file with temporary file
        final boolean discardOutput = this.outputFile == null;
        if (discardOutput) {
            try {
                this.outputFile = File.createTempFile(this.getClass().getSimpleName(), ".sql");
            } catch (IOException e) {
                throw new IOException("error creating temporary output file", e);
            }
            this.outputFile.deleteOnExit();
        }

        // Get properties
        final Properties properties = this.readProperties();
        properties.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS, "" + this.globallyQuotedIdentifiers);
        properties.put(AvailableSettings.GLOBALLY_QUOTED_IDENTIFIERS_SKIP_COLUMN_DEFINITIONS,
          "" + this.globallyQuotedIdentifiersSkipColumnDefinitions);

        // Create MetadataDescriptor
        this.log.info("Gathering Hibernate meta-data");
        final MetadataDescriptor metadataDescriptor = this.createMetadataDescriptor(properties);

        // Create exporter
        final Exporter exporter = this.createExporter(properties);

        // Configure exporter
        this.configureExporter(exporter, properties, metadataDescriptor);

        // Delete the output file to ensure it actually gets (re)generated
        this.outputFile.delete();

        // Run exporter
        this.log.info("Invoking Hibernate exporter");
        exporter.start();
        this.log.info(String.format("Wrote generated schema to %s", this.outputFile));

        // Clean up
        if (this.removePersistenceXml) {
            this.log.info(String.format("Removing %s %s", this.dialect != null ? "generated" : "user-supplied", persistenceXml));
            persistenceXml.delete();
            metaInf.delete();           // ok if this fails, that means directory is not empty
        }

        // Apply fixups
        this.applyFixups(properties);

        // Verify result
        this.verifyOutput();

        // Remove temporary file
        if (discardOutput)
            this.outputFile.delete();
    }

// Subclass Hooks

    protected void generatePersistenceXml(File persistenceXml) throws IOException {
        this.log.info(String.format("Generating %s", persistenceXml));
        Preconditions.checkArgument(this.persistenceXmlTemplate != null, "null persistenceXmlTemplate");

        // Substitute for placeholders in template
        final String content = this.persistenceXmlTemplate
          .replaceAll("@jpaName@", this.jpaUnit)
          .replaceAll("@dialect@", this.dialect);

        // Debug
        if (this.log.isDebugEnabled())
            this.log.debug(String.format("Generated persistence.xml:%n%s", content));

        // Write out result
        this.createDirectory(persistenceXml.getParentFile());
        try (Writer out = new OutputStreamWriter(new FileOutputStream(persistenceXml), StandardCharsets.UTF_8)) {
            out.write(content);
        } catch (IOException e) {
            throw new IOException(String.format("Error writing %s generated from template", persistenceXml), e);
        }
    }

    protected void createDirectory(File file) throws IOException {
        Preconditions.checkArgument(file != null, "null file");
        if (!file.isDirectory() && !file.mkdirs())
            throw new IOException("failed to create directory " + file);
    }

    protected MetadataDescriptor createMetadataDescriptor(Properties properties) {
        return new JpaMetadataDescriptor(this.jpaUnit, properties);
    }

    protected Exporter createExporter(Properties properties) {

        // Create and populate properties
        final Exporter exporter = ExporterFactory.createExporter(ExporterType.DDL);
        exporter.getProperties().putAll(properties);

        // Debug
        if (this.log.isDebugEnabled()) {
            this.log.debug(String.format("Exporter properties:%n%s",
              exporter.getProperties().entrySet().stream()
                .sorted(Comparator.comparing(entry -> (String)entry.getKey()))
                .map(entry -> String.format("    %50s = %s", entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("\n"))));
        }

        // Done
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

    protected Properties readProperties() {
        final Properties properties = new Properties();
        if (this.propertyFile != null) {
            if (this.log.isDebugEnabled())
                this.log.debug(String.format("Loading schema generation properties from %s", this.propertyFile));
            try (FileInputStream input = new FileInputStream(this.propertyFile)) {
                properties.load(input);
            } catch (IOException e) {
                throw new RuntimeException(String.format("Error loading %s: %s", this.propertyFile, e.getMessage()), e);
            }
        }
        return properties;
    }

    protected void applyFixups(Properties properties) {
        if (this.fixups == null || this.fixups.isEmpty())
            return;
        this.log.info(String.format("Applying %d fixup(s) to %s", this.fixups.size(), this.outputFile));
        try {
            final String charset = Optional.ofNullable(properties.getProperty(AvailableSettings.HBM2DDL_CHARSET_NAME))
              .orElse("utf-8");
            String ddl = new String(Files.readAllBytes(this.outputFile.toPath()), charset);
            boolean completedAllFixups = false;
            try {
                int index = 1;
                for (MatchReplace fixup : this.fixups) {
                    if (this.log.isDebugEnabled()) {
                        this.log.debug(String.format("Applying fixup #%d to generated schema...", index));
                        this.log.debug(String.format("  pattern: \"%s\"", fixup.getPattern()));
                        this.log.debug(String.format("  replace: \"%s\"", fixup.getReplacement()));
                        this.log.debug(String.format(" required: %s", fixup.isModificationRequired()));
                    }
                    final String ddl2;
                    try {
                        ddl2 = fixup.applyTo(ddl);
                    } catch (IllegalArgumentException e) {
                        throw new RuntimeException(String.format("Error applying fixup #%d: %s", index, e.getMessage()), e);
                    }
                    if (!ddl2.equals(ddl)) {
                        if (this.log.isDebugEnabled()) {
                            this.log.debug(String.format(
                              "Fixup #%d resulted in schema modification (old length %d, new length %d)",
                              index, ddl.length(), ddl2.length()));
                        }
                        ddl = ddl2;
                    } else {
                        if (this.log.isDebugEnabled())
                            this.log.debug(String.format("Fixup #%d resulted in no schema modification", index));
                        if (fixup.isModificationRequired()) {
                            throw new RuntimeException(String.format(
                              "Fixup #%d is required to modify the schema but no modification occurred", index));
                        }
                    }
                    index++;
                }
                completedAllFixups = true;
            } finally {     // even if a fixup fails, leave the output file as it was just prior to that fixup
                try {
                    Files.write(this.outputFile.toPath(), ddl.getBytes(charset));
                } catch (IOException e) {
                    if (completedAllFixups)
                        throw e;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format(
              "Error applying schema fixups to %s: %s", this.outputFile, e.getMessage()), e);
        }
    }

    protected void verifyOutput() {
        if (this.verifyFile == null) {
            this.log.info("Not verifying generated schema (no verification file configured)");
            return;
        }
        if (!this.verifyFile.exists()) {
            throw new RuntimeException(String.format(
              "Error verifying schema output: verification file %s does not exist", this.verifyFile));
        }
        this.log.info(String.format("Verifying generated schema:"));
        this.log.info(String.format("    Expected schema: %s", this.verifyFile));
        this.log.info(String.format("   Generated schema: %s", this.outputFile));
        final boolean match = this.compareAsEqual(this.outputFile, this.verifyFile);
        this.log.info(String.format("Schema verification %s", match ? "succeeded" : "failed"));
        if (!match)
            throw new RuntimeException("Generated schema differs from expected schema (schema migration may be needed)");
    }

    protected boolean compareAsEqual(File file1, File file2) {
        try {
            final byte[] bytes1 = Files.readAllBytes(file1.toPath());
            final byte[] bytes2 = Files.readAllBytes(file2.toPath());
            return Arrays.equals(bytes1, bytes2);
        } catch (IOException e) {
            throw new RuntimeException(String.format("Error comparing file %s to %s: %s", file1, file2,  e.getMessage()), e);
        }
    }
}
