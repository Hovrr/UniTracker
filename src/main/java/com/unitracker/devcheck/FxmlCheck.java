package com.unitracker.devcheck;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Loads Dashboard.fxml for real and reports whether it wired up.
 *
 * <p>WHY THIS EXISTS: FXML is resolved by reflection at load time, so a typo in
 * an {@code onAction="#handler"}, an {@code fx:id} whose field was never
 * declared, or a missing {@code <?import?>} all compile perfectly and then throw
 * the instant the app starts. javac cannot see any of it. This is the cheapest
 * thing that turns those into a build-time failure.
 *
 * <p>RUN IT AGAINST A THROWAWAY DATABASE. {@code DashboardController#initialize}
 * reads and can write skills (applyStalledStatuses), and DatabaseHelper derives
 * its path from {@code user.home}. Always launch with
 * {@code -Duser.home=<some temp dir>} so a check run can never touch the real
 * ~/.unitracker/unitracker.db. See devcheck-fxml.py, which does exactly that.
 */
public class FxmlCheck extends Application {

    @Override
    public void start(Stage stage) {
        try {
            // The controller expects the schema to exist; the app normally does
            // this in MainApp before loading any FXML.
            com.unitracker.db.DatabaseHelper.getInstance().initializeDatabase();

            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/unitracker/view/Dashboard.fxml"));
            Parent root = loader.load();

            // Applying the stylesheet catches a malformed CSS file too, which is
            // otherwise only ever visible as an ugly warning in a running app.
            Scene scene = new Scene(root, 1440, 900);
            scene.getStylesheets().add(
                    getClass().getResource("/com/unitracker/css/styles.css").toExternalForm());

            System.out.println("FXML_OK");
            Platform.exit();
        } catch (Throwable t) {
            System.out.println("FXML_FAIL");
            t.printStackTrace();
            Platform.exit();
            // Non-zero so the calling script fails loudly rather than scrolling past.
            Runtime.getRuntime().halt(1);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
