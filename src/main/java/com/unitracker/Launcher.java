package com.unitracker;

/**
 * Deployment-only entry point for the SHADED fat-jar / launch4j .exe build
 * (see the "windows-exe" Maven profile in pom.xml). This is deliberately
 * NOT the class referenced by the {@code mainClass} property used for
 * day-to-day {@code mvn javafx:run} - that one still points straight at
 * {@link MainApp}, which is correct and necessary there: javafx-maven-plugin
 * launches the app via the real JavaFX module path, so the problem this
 * class works around never comes up in that context.
 * <p>
 * THE PROBLEM THIS SOLVES: once JavaFX is merged into a single jar and
 * launched the plain way (java -jar ..., or launch4j's .exe wrapper around
 * that same mechanism) rather than via the module path, the JDK 11+ java
 * launcher runs one specific check: if the class named as Main-Class
 * itself extends {@code javafx.application.Application}, and JavaFX isn't
 * visible as proper named modules, it refuses to start at all - printing
 * "Error: JavaFX runtime components are missing" - EVEN THOUGH every
 * JavaFX class is genuinely sitting right there on the classpath. That
 * check only inspects the class launched DIRECTLY; it has no way to see
 * what a plain class's own main() method goes on to call. So a class that
 * does NOT itself extend Application, and simply hands off to one from
 * inside main(), never trips the check, while still starting the exact
 * same application.
 * <p>
 * This is why pom.xml's shade-plugin ManifestResourceTransformer and the
 * launch4j classPath both need to point their mainClass at
 * {@code com.unitracker.Launcher}, not {@code com.unitracker.MainApp}
 * directly, for the packaged .exe specifically.
 */
public class Launcher {

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
