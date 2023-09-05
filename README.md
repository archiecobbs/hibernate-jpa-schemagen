# hibernate-jpa-schemagen
Sensible Maven schema DDL generator for projects using Hibernate and JPA

### Scenario

This Maven plugin tries to address the following scenario:

* You are building a project using Hibernate and JPA
* You want to automatically generate a schema DDL file during the build
* You want to compare the generated schema to an expected output
* You want the build to fail if there are any differences, because that means either:
  * You made an unintentional change to one of your model classes, or
  * You need to add a schema migration to accomodate the intended change

And you might also have one of these issues:

* You can **not** assume a JDBC database connection is available during your build
* You may **not** have any `META-INF/persistence.xml` (perhaps you are using [Spring Boot Data](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#data.sql.jpa-and-spring-data.repositories) which doesn't require one)
* You **do** want to edit the generated schema DDL with some minor tweaks

### Overview

What this Maven plugin does:

* Writes the DDL schema generated from JPA meta-data to an output file without requiring a database connection
* Allows you to easily apply regular expression match/replace edits to the generated schema
* Checks that the generated schema matches what you expect, and fails the build if it doesn't

### Configuring the Plugin

Each version of this plugin has a version number that matches the version of Hibernate against which it was compiled in the major and minor components (i.e., first two numbers), so for example for Hibernate version `5.3.31.Final` you should use the latest 5.3.x version of this plugin.

Example configuration:
```xml
<plugin>
    <groupId>org.dellroad</groupId>
    <artifactId>hibernate-jpa-schemagen</artifactId>
    <version>5.3.1</version>
    <executions>
        <execution>
            <id>schema_verify</id>
            <configuration>
                <jpaUnit>myjpa</jpaUnit>
                <removePersistenceXml>true</removePersistenceXml>
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
Configuration options:
* `<jpaUnit>` JPA persistence unit name. Required.
* `<classRoot>` Directory where your classes and `META-INF/persistence.xml` can be found. Default `${project.build.directory}/classes`.
* `<removePersistenceXml>` Remove the `META-INF/persistence.xml` used when done. Default false.
* `<propertyFile>` Optional properties file. Allows adding/overriding properties configured by the plugin.
* `<outputFile>` Output file. Default `${project.build.directory}/generated-resources/schema.ddl`.
* `<verifyFile>` Verification file. Default `${project.basedir}/src/schema/schema.ddl`.
* `<drop>` Include `DROP TABLE` statements. Default false.
* `<delimiter>` The delimiter that separates SQL statements. Default `;`.
* `<format>` Whether to format SQL statements. Default true.

By defalt, the plugin runs in the `process-classes` Maven lifecycle phase.

### `META-INF/persistence.xml`

Even if it's not needed at runtime, during the build a `META-INF/persistence.xml` file is required for this plugin to work. The plugin expects to find it under the same root as your compiled JPA classes (usually `target/classes`). This means you should define it as a `<resource>` that gets copied into place.

The `META-INF/persistence.xml` file should at least define the `hibernate.dialect` property; it does <b>not</b> need to specify a JDBC connection (although you can if you want).

Here's an example of an adequate file:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<persistence version="3.0" xmlns="https://jakarta.ee/xml/ns/persistence">
    <persistence-unit name="myjpa">
        <properties>
            <property name="hibernate.dialect" value="org.hibernate.dialect.MySQLDialect"/>
        </properties>
    </persistence-unit>
</persistence>
```

If you don't need `META-INF/persistence.xml` at runtime, you can configure `<removePersistenceXml>true</removePersistenceXml>` to have the plugin remove it when done.

### Build Exceptions

You can safely ignore any "The application must supply JDBC connections" exceptions that are spit out by Hibernate during the build; these are a sign that your lack of a need for a database connection is working!
