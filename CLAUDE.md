# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Spring Boot 4.0.2 application using Java 21 and Gradle for build management. The project name is "batchstudy" (group: com.test).

## Build Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.test.batchstudy.BatchstudyApplicationTests"

# Run a single test method
./gradlew test --tests "com.test.batchstudy.BatchstudyApplicationTests.contextLoads"

# Clean build
./gradlew clean build
```

## Architecture

Standard Spring Boot project structure:
- `src/main/java/com/test/batchstudy/` - Application source code
- `src/main/resources/` - Configuration files (application.properties)
- `src/test/java/com/test/batchstudy/` - Test classes

Entry point: `BatchstudyApplication.java` with `@SpringBootApplication` annotation.