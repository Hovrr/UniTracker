package com.unitracker;

import com.unitracker.db.DatabaseHelper;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.scene.image.Image;
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

        // ITEM 1.6 - 1080p MAXIMIZE FIX, PART 1 OF 2.
        //
        // The old code was `new Scene(root, 1440, 900)` with a hard
        // setMinWidth(1100)/setMinHeight(700). On a 2K screen that is
        // comfortably inside the desktop, which is why it looked fine there.
        // On 1920x1080 the usable height after the taskbar is ~1040, so a
        // 900-tall window plus decorations is already close to the edge, and
        // the 700 minimum cannot shrink far enough for the layout to reflow -
        // the window ends up larger than what the OS will actually paint,
        // which is exactly what shows up as black bands and clipped panels.
        //
        // Fix: derive every size from the CURRENT screen's visual bounds
        // (which already exclude the taskbar) instead of hardcoding pixels,
        // and cap the minimums so they can never demand more room than the
        // display physically has. getVisualBounds() is JavaFX-native and
        // needs no dependency.
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double startWidth = Math.min(1440, screen.getWidth() * 0.92);
        double startHeight = Math.min(900, screen.getHeight() * 0.92);

        Scene scene = new Scene(root, startWidth, startHeight);
        scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

        primaryStage.setTitle("Uni Tracker - Multi-Skill Progress Dashboard");
        primaryStage.setScene(scene);
        // min() so a small or scaled display can still shrink the window
        // enough for the layout to reflow rather than being clipped.
        primaryStage.setMinWidth(Math.min(1100, screen.getWidth()));
        primaryStage.setMinHeight(Math.min(700, screen.getHeight()));
        loadWindowIcons(primaryStage);
        primaryStage.show();
    }

    /**
     * Puts the app logo on the Windows title bar and taskbar.
     *
     * <p>IMPORTANT - .ico WILL NOT WORK: JavaFX's Image decoder handles PNG,
     * JPEG, GIF and BMP only, so the icon.ico already in the project root
     * (which launch4j uses for the built .exe - that part is correct and
     * unchanged) cannot be reused here. It has to be a PNG under
     * src/main/resources.
     *
     * <p>Several sizes are added because Windows picks the closest match per
     * context - 16px for the title bar, 32px for the taskbar, 48/256 for
     * Alt-Tab and the desktop shortcut. Supplying only a large one leaves
     * Windows to downscale it, which is what makes an icon look muddy in the
     * title bar. Any that are missing are simply skipped.
     */
    private void loadWindowIcons(Stage stage) {
        for (String size : new String[]{"16", "32", "48", "256"}) {
            String path = "/com/unitracker/images/icon-" + size + ".png";
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    stage.getIcons().add(new Image(is));
                }
            } catch (IOException e) {
                System.err.println("[Icon] Failed to load " + path + ": " + e.getMessage());
            }
        }
        if (stage.getIcons().isEmpty()) {
            System.out.println("[Icon] No PNG icons found in /com/unitracker/images/ - "
                    + "using the default JavaFX icon. Note JavaFX cannot read the root icon.ico.");
        }
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
