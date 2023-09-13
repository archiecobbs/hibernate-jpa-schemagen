# hibernate-jpa-schemagen
Sensible Maven schema DDL generator for projects using Hibernate and JPA

### Scenario

This Maven plugin tries to address the following scenario:

* You are building a project using Hibernate and JPA
* You want to automatically generate a schema DDL file during the build
* You want to compare the generated schema to an expected output
* You want the build to fail if there are any differences, because that means either:
  * You made an unintentional change to one of your model classes, or
  * It's a good reminder that you need to add a schema migration

And you might also have one of these issues:

* You **cannot** assume a JDBC database connection is available during your build
* You may **not** have any `META-INF/persistence.xml` (perhaps you are using [Spring Boot Data](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#data.sql.jpa-and-spring-data.repositories) which doesn't require one)
* You **do** want to edit the generated schema DDL with some minor tweaks

### Overview

What this Maven plugin does:

* Writes the DDL schema generated from JPA meta-data to an output file without requiring a database connection
* Allows you to easily apply regular expression match/replace edits to the generated schema
* Checks that the generated schema matches what you expect, and fails the build if it doesn't

### Configuring the Plugin

Each version of this plugin has a version number that matches the version of Hibernate against which it was compiled in the major and minor components (i.e., first two numbers), so for example for Hibernate version `5.6.15.Final` you should use the latest 5.6.x version of this plugin.

Example configuration:
```xml
<plugin>
    <groupId>org.dellroad</groupId>
    <artifactId>hibernate-jpa-schemagen</artifactId>
    <version>5.6.2</version>
    <executions>
        <execution>
            <id>schema_verify</id>
            <configuration>
                <dialect>org.hibernate.dialect.MySQLDialect</dialect>
                <outputFile>${project.build.directory}/schema/schema.sql</outputFile>
                <verifyFile>${project.basedir}/src/schema/expected.sql</verifyFile>
                <fixups>
                    <fixup>
                        <pattern>InnoDB;</pattern>
                        <replacement>InnoDB default charset=utf8mb4 collate=utf8mb4_bin;</replacement>
                    </fixup>
                </fixups>
            </configuration>
            <goals>
                <goal>export-jpa-schema</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Primary configuration options:
* `<jpaUnit>` JPA persistence unit name (for user-supplied `META-INF/persistence.xml`).
* `<dialect>` Hibernate dialect class (for plugin-generated `META-INF/persistence.xml`).
* `<verifyFile>` Verification file, or empty string to not verify. Default `${project.basedir}/src/schema/schema.ddl`.
* `<removePersistenceXml>` Whether to discard `META-INF/persistence.xml` when done.

Other configuration options:
* `<classRoot>` Directory where your entity classes and `META-INF/persistence.xml` are found. Default `${project.build.directory}/classes`.
* `<outputFile>` Output file. Default `${project.build.directory}/generated-resources/schema.ddl`.
* `<propertyFile>` Optional properties file. Overrides properties configured by the plugin.
* `<persistenceXmlTemplate>` Classpath location for plugin-generated `META-INF/persistence.xml` template. Default `META-INF/hibernate-jpa-schemagen/persistence-template.xml`.
* `<drop>` Include `DROP TABLE` statements. Default false.
* `<delimiter>` The delimiter that separates SQL statements. Default `;`.
* `<format>` Whether to format SQL statements. Default true.

By default, the plugin runs in the `process-classes` Maven lifecycle phase.

### `META-INF/persistence.xml`

Even if it's not needed at runtime, a `META-INF/persistence.xml` file is required during the build for this plugin to work. You can either provide a `META-INF/persistence.xml` file if you already have one, or have a temporary one generated for you.

#### Provide Your Own `META-INF/persistence.xml`

If you want to provide your own `META-INF/persistence.xml` file, the plugin expects to find it under the same root as your compiled JPA classes (usually `target/classes`). This means you should include it as a Maven `<resource>` that gets copied into place.

The `META-INF/persistence.xml` file should at least define the `hibernate.dialect` property; it does <b>not</b> need to specify a JDBC connection (although you can if you want).

Here's an example of an adequate file:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<persistence version="2.1" xmlns="http://xmlns.jcp.org/xml/ns/persistence">
    <persistence-unit name="myjpa">
        <properties>
            <property name="hibernate.dialect" value="org.hibernate.dialect.MySQL5InnoDBDialect"/>
        </properties>
    </persistence-unit>
</persistence>
```

If you don't need `META-INF/persistence.xml` at runtime, you can use `<removePersistenceXml>` to have the plugin remove it from `target/classes` when it's done.

When providing your own `META-INF/persistence.xml`:
* `<jpaUnit>` is required and must match the file
* `<dialect>` is not allowed
* `<removePersistenceXml>` defaults to `false`

#### Generate a Templorary `META-INF/persistence.xml`

If you don't have a `META-INF/persistence.xml`, then the plugin can generate a temporary one for you that looks like the example above.

When letting the plugin generate `META-INF/persistence.xml` for you:

* `<dialect>` is required
* `<jpaUnit>` is not allowed
* `<removePersistenceXml>` defaults to `true`

You can override the template used to generate the file using `<persistenceXmlTemplate>`. When doing this, you should put your template on the plugin's classpath by adding an appropriate `<dependency>`. The template is assumed to be UTF-8 encoded.

### Build Exceptions

You can safely ignore any "The application must supply JDBC connections" warnings that are spit out by Hibernate during the build.

These are a sign that your lack of a need for a JDBC database connection is working!
