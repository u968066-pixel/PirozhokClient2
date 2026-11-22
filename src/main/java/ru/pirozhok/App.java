package ru.pirozhok.launcher;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class App extends Application {

    private Config config;
    private TextField nickField;
    private Slider ramSlider;
    private Label ramLabel;
    private Button playButton;
    private ProgressBar progressBar;
    private Label statusLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        config = Config.load();

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0B0D10;");

        VBox sidebar = new VBox(20);
        sidebar.setPadding(new Insets(30, 20, 30, 20));
        sidebar.setStyle("-fx-background-color: #14171C; -fx-border-color: #1C2027; -fx-border-width: 0 1 0 0;");
        sidebar.setPrefWidth(200);

        Label logo = new Label("PIROZHOK");
        logo.setTextFill(Color.web("#2DD4BF"));
        logo.setFont(Font.font("System", FontWeight.BOLD, 24));

        Button btnHome = createMenuButton("Главная", true);
        Button btnSettings = createMenuButton("Настройки", false);

        sidebar.getChildren().addAll(logo, btnHome, btnSettings);

        VBox content = new VBox(20);
        content.setPadding(new Insets(40));
        content.setAlignment(Pos.TOP_LEFT);

        Label title = new Label("Добро пожаловать в Pirozhok Launcher");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("System", FontWeight.BOLD, 28));

        Label subtitle = new Label("Готов к новым приключениям?");
        subtitle.setTextFill(Color.web("#8A94A3"));
        subtitle.setFont(Font.font("System", 16));

        VBox settingsBox = new VBox(15);
        settingsBox.setStyle("-fx-background-color: #14171C; -fx-padding: 20; -fx-background-radius: 10;");
        settingsBox.setMaxWidth(500);

        HBox nickBox = new HBox(10);
        nickBox.setAlignment(Pos.CENTER_LEFT);
        Label nickLabel = new Label("Ник:");
        nickLabel.setTextFill(Color.WHITE);
        nickField = new TextField(config.nick);
        nickField.setStyle("-fx-background-color: #1C2027; -fx-text-fill: white; -fx-prompt-text-fill: #8A94A3;");
        nickField.setPrefWidth(200);
        nickBox.getChildren().addAll(nickLabel, nickField);

        HBox ramBox = new HBox(10);
        ramBox.setAlignment(Pos.CENTER_LEFT);
        Label ramText = new Label("Память (ГБ):");
        ramText.setTextFill(Color.WHITE);
        ramSlider = new Slider(2, 16, config.ramGb);
        ramSlider.setShowTickMarks(true);
        ramSlider.setShowTickLabels(true);
        ramSlider.setMajorTickUnit(2);
        ramSlider.setBlockIncrement(1);
        ramLabel = new Label(config.ramGb + " ГБ");
        ramLabel.setTextFill(Color.web("#2DD4BF"));
        ramSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int val = newVal.intValue();
            ramLabel.setText(val + " ГБ");
        });
        ramBox.getChildren().addAll(ramText, ramSlider, ramLabel);

        settingsBox.getChildren().addAll(nickBox, ramBox);

        playButton = new Button("ИГРАТЬ");
        playButton.setStyle("-fx-background-color: linear-gradient(to right, #7C3AED, #2DD4BF); -fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold; -fx-padding: 15 60; -fx-background-radius: 30; -fx-cursor: hand;");
        playButton.setOnAction(e -> startGame());

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(500);
        progressBar.setStyle("-fx-accent: #2DD4BF;");
        progressBar.setVisible(false);

        statusLabel = new Label("Готов к запуску");
        statusLabel.setTextFill(Color.web("#8A94A3"));

        VBox bottomBox = new VBox(10, playButton, progressBar, statusLabel);
        bottomBox.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(title, subtitle, settingsBox, bottomBox);

        root.setLeft(sidebar);
        root.setCenter(content);

        Scene scene = new Scene(root, 900, 600);
        primaryStage.setTitle("Pirozhok Launcher");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    private Button createMenuButton(String text, boolean active) {
        Button btn = new Button(text);
        String bg = active ? "-fx-background-color: #1C2027; -fx-text-fill: #2DD4BF;" : "-fx-background-color: transparent; -fx-text-fill: #8A94A3;";
        btn.setStyle(bg + " -fx-font-size: 16px; -fx-alignment: center-left; -fx-padding: 10 20; -fx-background-radius: 8; -fx-cursor: hand;");
        btn.setMaxWidth(Double.MAX_VALUE);
        return btn;
    }

    private void startGame() {
        config.nick = nickField.getText().trim();
        if (config.nick.isEmpty()) config.nick = "Player";
        config.ramGb = (int) ramSlider.getValue();
        config.save();

        playButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        statusLabel.setText("Подготовка...");

        new Thread(() -> {
            try {
                GameInstaller installer = new GameInstaller(config, (status, fraction) -> {
                    Platform.runLater(() -> {
                        statusLabel.setText(status);
                        progressBar.setProgress(fraction);
                    });
                });
                installer.run();
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Ошибка: " + ex.getMessage());
                    statusLabel.setTextFill(Color.web("#FF5555"));
                    playButton.setDisable(false);
                });
            }
        }).start();
    }
}
