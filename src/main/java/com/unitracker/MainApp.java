package com.unitracker;

import com.unitracker.db.DatabaseHelper;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;

/**
 * Application entry point.
 *
 * Startup order matters here:
 *   1. Load custom fonts BEFORE the scene is built, so the very first
 *      layout pass already has Space Grotesk / Poppins available.
 *   2. Initialize the SQLite database BEFORE the FXML loads, since
 *      DashboardController's initialize() immediately queries it.
 *   3. Load Dashboard.fxml + styles.css and show the stage.
 */
public class MainApp extends Application {

    private static final String FXML_PATH = "/com/unitracker/view/Dashboard.fxml";
    private static final String CSS_PATH = "/com/unitracker/css/styles.css";

    @Override
    public void start(Stage primaryStage) throws IOException {
        loadCustomFonts();

        DatabaseHelper.getInstance().initializeDatabase();

        FXMLLoader loader = new FXMLLoader(getClass().getResource(FXML_PATH));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1440, 900);
        scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

        primaryStage.setTitle("Uni Tracker \u2014 Multi-Skill Progress Dashboard");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1100);
        primaryStage.setMinHeight(700);
        primaryStage.show();
    }

    @Override
    public void stop() {
        // Release the SQLite file lock cleanly when the window is closed.
        DatabaseHelper.getInstance().closeConnection();
    }

    /**
     * Loads Space Grotesk / Poppins from resources/com/unitracker/fonts.
     * See the fonts folder's PLACE_FONTS_HERE.txt for exact download links.
     * If a .ttf is missing, JavaFX just falls back to the platform default
     * font - the app still runs correctly, just without the custom typeface.
     */
    private void loadCustomFonts() {
        loadFont("/com/unitracker/fonts/SpaceGrotesk-Regular.ttf");
        loadFont("/com/unitracker/fonts/SpaceGrotesk-Bold.ttf");
        loadFont("/com/unitracker/fonts/Poppins-Regular.ttf");
        loadFont("/com/unitracker/fonts/Poppins-Medium.ttf");
        loadFont("/com/unitracker/fonts/Poppins-SemiBold.ttf");
    }

    private void loadFont(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                Font.loadFont(is, 12);
            } else {
                System.out.println("[Fonts] Not found, using platform default instead: " + resourcePath);
            }
        } catch (Exception e) {
            System.err.println("[Fonts] Failed to load " + resourcePath + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
