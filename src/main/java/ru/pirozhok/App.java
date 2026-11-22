package ru.pirozhok;

import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class App extends Application {
    private static final Color VIOLET = Color.web("#8b5cf6");
    private static final Color CYAN = Color.web("#22d3ee");

    private final Config cfg = Config.load();
    private final StackPane host = new StackPane();
    private final Label status = new Label("Готов к запуску");
    private final Label percent = new Label("0%");
    private final NeonBar bar = new NeonBar();
    private final Button play = new Button("ИГРАТЬ");
    private final TextField nick = new TextField();
    private Node current, homePage, settingsPage, optimizerPage;
    private ToggleButton optNav;
    private VBox optLog;
    private Button optRun;
    private boolean optBusy;
    private double dragX, dragY;

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("Pirozhok Launcher");
        homePage = buildHome();
        settingsPage = buildSettings();
        optimizerPage = buildOptimizer();

        VBox right = new VBox(titleBar(stage), host);
        VBox.setVgrow(host, Priority.ALWAYS);
        BorderPane layout = new BorderPane(right);
        layout.setLeft(buildSidebar(stage));

        StackPane shell = new StackPane(buildGlow(), layout);
        shell.getStyleClass().add("shell");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(52);
        clip.setArcHeight(52);
        clip.widthProperty().bind(shell.widthProperty());
        clip.heightProperty().bind(shell.heightProperty());
        shell.setClip(clip);

        Scene scene = new Scene(shell, 1020, 640);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        show(homePage);
    }

    private Pane buildGlow() {
        Pane p = new Pane();
        p.setMouseTransparent(true);
        p.getChildren().addAll(
                glow("#8b5cf6", 780, 110, 330, 0.30, 70, 50, 9),
                glow("#22d3ee", 330, 600, 300, 0.22, -60, -40, 11),
                glow("#8b5cf6", 120, 220, 200, 0.16, 50, -50, 13));
        return p;
    }

    private Circle glow(String color, double x, double y, double r, double alpha, double bx, double by, double secs) {
        Circle c = new Circle(x, y, r);
        c.setFill(new RadialGradient(0, 0, x, y, r, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web(color, alpha)), new Stop(1, Color.web(color, 0))));
        TranslateTransition t = new TranslateTransition(Duration.seconds(secs), c);
        t.setByX(bx);
        t.setByY(by);
        t.setAutoReverse(true);
        t.setCycleCount(Animation.INDEFINITE);
        t.setInterpolator(Interpolator.EASE_BOTH);
        t.play();
        return c;
    }

    private void drag(Node n, Stage s) {
        n.setOnMousePressed(e -> { dragX = e.getScreenX() - s.getX(); dragY = e.getScreenY() - s.getY(); });
        n.setOnMouseDragged(e -> { s.setX(e.getScreenX() - dragX); s.setY(e.getScreenY() - dragY); });
    }

    private HBox titleBar(Stage stage) {
        Button min = new Button("—");
        Button close = new Button("✕");
        min.getStyleClass().add("win-btn");
        close.getStyleClass().addAll("win-btn", "win-close");
        min.setOnAction(e -> stage.setIconified(true));
        close.setOnAction(e -> Platform.exit());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bar = new HBox(6, sp, min, close);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(14, 18, 0, 18));
        drag(bar, stage);
        return bar;
    }

    private VBox buildSidebar(Stage stage) {
        Text logo = new Text("PIROZHOK");
        logo.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 24));
        logo.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, VIOLET), new Stop(1, CYAN)));
        Label sub = new Label("LAUNCHER");
        sub.getStyleClass().add("caption");
        VBox brand = new VBox(2, logo, sub);
        brand.setPadding(new Insets(10, 8, 28, 8));
        drag(brand, stage);

        ToggleGroup g = new ToggleGroup();
        g.selectedToggleProperty().addListener((o, old, nw) -> { if (nw == null && old != null) old.setSelected(true); });
        ToggleButton home = nav("⌂", "Главная", g, () -> homePage);
        ToggleButton mods = nav("✦", "Моды", g, this::buildMods);
        optNav = nav("⚡", "Оптимизатор", g, () -> optimizerPage);
        ToggleButton set = nav("⚙", "Настройки", g, () -> settingsPage);
        home.setSelected(true);

        Region sp = new Region();
        VBox.setVgrow(sp, Priority.ALWAYS);
        Label ver = new Label("v" + Config.VERSION + "  •  MC " + Config.MC);
        ver.getStyleClass().add("caption");

        VBox box = new VBox(8, brand, home, mods, optNav, set, sp, ver);
        box.getStyleClass().add("sidebar");
        box.setPrefWidth(230);
        box.setPadding(new Insets(26, 18, 22, 18));
        return box;
    }

    private ToggleButton nav(String icon, String text, ToggleGroup g, Supplier<Node> page) {
        ToggleButton b = new ToggleButton(icon + "   " + text);
        b.setToggleGroup(g);
        b.getStyleClass().add("nav-btn");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> show(page.get()));
        return b;
    }

    private void show(Node page) {
        if (page == current) return;
        current = page;
        host.getChildren().setAll(page);
        page.setOpacity(0);
        page.setTranslateY(14);
        FadeTransition ft = new FadeTransition(Duration.millis(260), page);
        ft.setToValue(1);
        TranslateTransition tt = new TranslateTransition(Duration.millis(260), page);
        tt.setToY(0);
        tt.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(ft, tt).play();
    }

    private VBox buildHome() {
        nick.setText(cfg.nick);
        nick.setPromptText("Ник (offline)");
        nick.getStyleClass().add("input");
        nick.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> ver = new ComboBox<>(FXCollections.observableArrayList(Config.MC + " Fabric"));
        ver.getSelectionModel().selectFirst();
        ver.setPrefWidth(220);
        ver.setPrefHeight(48);

        VBox nickBox = field("НИК", nick);
        HBox.setHgrow(nickBox, Priority.ALWAYS);
        HBox row = new HBox(16, nickBox, field("ВЕРСИЯ", ver));

        play.getStyleClass().add("play-btn");
        play.setMaxWidth(Double.MAX_VALUE);
        DropShadow ds = new DropShadow(28, Color.web("#8b5cf6", 0.6));
        play.setEffect(ds);
        Timeline pulse = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(ds.radiusProperty(), 20)),
                new KeyFrame(Duration.seconds(1.6), new KeyValue(ds.radiusProperty(), 42, Interpolator.EASE_BOTH)));
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
        play.setOnMouseEntered(e -> scale(play, 1.02));
        play.setOnMouseExited(e -> scale(play, 1.0));
        play.setOnAction(e -> play());

        Button optBtn = ghost("⚡  Оптимизировать ПК", () -> openOptimizer(true));
        optBtn.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(14, row, play, optBtn);
        card.getStyleClass().add("glass");
        card.setMaxWidth(680);

        status.getStyleClass().add("status");
        percent.getStyleClass().add("value");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        VBox statusCard = new VBox(14, new HBox(status, sp, percent), bar);
        statusCard.getStyleClass().add("glass");
        statusCard.setMaxWidth(680);

        Label hint = new Label("Java 21, библиотеки Mojang, Fabric и мод PirojokVisual скачаются и установятся автоматически.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        hint.setMaxWidth(680);

        VBox page = new VBox(20, h1("Главная"), muted("Введи ник и жми «ИГРАТЬ»."), card, statusCard, hint);
        page.setPadding(new Insets(12, 40, 36, 40));
        return page;
    }

    private VBox buildSettings() {
        Slider ram = new Slider(2, 16, cfg.ramGb);
        ram.setMajorTickUnit(1);
        ram.setMinorTickCount(0);
        ram.setSnapToTicks(true);
        HBox.setHgrow(ram, Priority.ALWAYS);
        Label ramLbl = new Label(cfg.ramGb + " ГБ");
        ramLbl.getStyleClass().add("value");
        ram.valueProperty().addListener((o, a, b) -> {
            cfg.ramGb = b.intValue();
            ramLbl.setText(cfg.ramGb + " ГБ");
            cfg.save();
        });
        HBox ramRow = new HBox(16, ram, ramLbl);
        ramRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox close = new CheckBox("Закрывать лаунчер после запуска игры");
        close.setSelected(cfg.closeOnLaunch);
        close.setOnAction(e -> { cfg.closeOnLaunch = close.isSelected(); cfg.save(); });

        Button openGame = ghost("Открыть папку игры", () -> openDir(Config.GAME));

        VBox card = new VBox(18, caption("ОПЕРАТИВНАЯ ПАМЯТЬ"), ramRow, close, openGame);
        card.getStyleClass().add("glass");
        card.setMaxWidth(680);

        VBox page = new VBox(20, h1("Настройки"), muted("Параметры запуска игры."), card);
        page.setPadding(new Insets(12, 40, 36, 40));
        return page;
    }

    private Node buildMods() {
        VBox list = new VBox(10);
        boolean has = Files.exists(Config.MODS.resolve("PirojokVisual.jar"));
        list.getChildren().add(modRow("PirojokVisual", "Визуальный мод Pirozhok Client",
                has ? "УСТАНОВЛЕН" : "УСТАНОВИТСЯ ПРИ ЗАПУСКЕ", has));
        try (Stream<Path> s = Files.list(Config.MODS)) {
            s.filter(p -> p.getFileName().toString().endsWith(".jar")
                            && !p.getFileName().toString().equals("PirojokVisual.jar"))
                    .sorted()
                    .forEach(p -> list.getChildren().add(modRow(p.getFileName().toString(), "Дополнительный мод", "УСТАНОВЛЕН", true)));
        } catch (IOException ignored) { }

        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("clear-scroll");
        sp.setMaxWidth(680);
        VBox.setVgrow(sp, Priority.ALWAYS);

        VBox page = new VBox(20, h1("Моды"), muted("PirojokVisual.jar берётся из папки libs и копируется в mods при каждом запуске."),
                sp, ghost("Открыть папку модов", () -> openDir(Config.MODS)));
        page.setPadding(new Insets(12, 40, 36, 40));
        return page;
    }

    private HBox modRow(String name, String desc, String badge, boolean ok) {
        Label n = new Label(name);
        n.getStyleClass().add("mod-name");
        Label d = new Label(desc);
        d.getStyleClass().add("muted");
        Label b = new Label(badge);
        b.getStyleClass().addAll("badge", ok ? "badge-ok" : "badge-wait");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(12, new VBox(2, n, d), sp, b);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("glass");
        return row;
    }

    private Node buildOptimizer() {
        CheckBox boost = new CheckBox("FPS-буст: Sodium, Lithium и др. + настройки графики, память и JVM");
        boost.setWrapText(true);
        boost.setSelected(cfg.boost);
        boost.setOnAction(e -> { cfg.boost = boost.isSelected(); cfg.save(); });

        CheckBox prio = new CheckBox("Высокий приоритет процессу Minecraft при запуске");
        prio.setSelected(cfg.highPriority);
        prio.setOnAction(e -> { cfg.highPriority = prio.isSelected(); cfg.save(); });

        optLog = new VBox(6);
        optLog.getStyleClass().add("log-box");
        optLog.getChildren().add(muted("Нажми «Запустить оптимизацию» — результат появится здесь."));

        optRun = new Button("Запустить оптимизацию");
        optRun.getStyleClass().add("accent-btn");
        optRun.setMaxWidth(Double.MAX_VALUE);
        optRun.setOnAction(e -> runOptimizer());

        VBox actions = new VBox(16, caption("АВТОМАТИЧЕСКИЕ ДЕЙСТВИЯ"), boost, prio, optRun, optLog);
        actions.getStyleClass().add("glass");
        actions.setMaxWidth(680);

        VBox tips = new VBox(12, caption("ЧЕК-ЛИСТ ПЕРЕД ИГРОЙ"));
        for (String t : Optimizer.CHECKLIST) {
            CheckBox c = new CheckBox(t);
            c.setWrapText(true);
            tips.getChildren().add(c);
        }
        tips.getStyleClass().add("glass");
        tips.setMaxWidth(680);

        VBox content = new VBox(20, h1("Оптимизатор"),
                muted("Только безопасные действия: без правки реестра и сторонних программ."), actions, tips);
        content.setPadding(new Insets(12, 40, 36, 40));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("clear-scroll");
        return sp;
    }

    private void openOptimizer(boolean autoRun) {
        optNav.setSelected(true);
        show(optimizerPage);
        if (autoRun) runOptimizer();
    }

    private void runOptimizer() {
        if (optBusy) return;
        optBusy = true;
        optRun.setDisable(true);
        optRun.setText("Выполняется…");
        optLog.getChildren().clear();
        Task<Void> t = new Task<>() {
            @Override protected Void call() {
                Optimizer.runAll(line -> Platform.runLater(() -> addLog(line)), cfg.boost);
                return null;
            }
        };
        Runnable done = () -> { optBusy = false; optRun.setDisable(false); optRun.setText("Запустить оптимизацию"); };
        t.setOnSucceeded(e -> done.run());
        t.setOnFailed(e -> { addLog("✗ Ошибка: " + t.getException()); done.run(); });
        Thread th = new Thread(t, "optimizer");
        th.setDaemon(true);
        th.start();
    }

    private void addLog(String line) {
        Label l = new Label(line);
        l.setWrapText(true);
        l.getStyleClass().add(line.startsWith("✓") ? "log-ok" : line.startsWith("✗") ? "log-bad" : "muted");
        optLog.getChildren().add(l);
    }

    private void play() {
        String n = nick.getText().trim();
        if (!n.matches("[A-Za-z0-9_]{3,16}")) {
            setStatus("Ник: 3–16 символов — латиница, цифры, _", true);
            shake(nick);
            return;
        }
        cfg.nick = n;
        cfg.save();
        play.setDisable(true);
        play.setText("ЗАПУСК…");
        setStatus("Подготовка…", false);
        setProgress(0.01);

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                new GameInstaller(cfg, (s, f) -> Platform.runLater(() -> { setStatus(s, false); setProgress(f); })).run();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            play.setDisable(false);
            play.setText("ИГРАТЬ");
            setStatus("Игра запущена — приятной игры!", false);
            if (cfg.closeOnLaunch) Platform.exit();
        });
        task.setOnFailed(e -> {
            play.setDisable(false);
            play.setText("ИГРАТЬ");
            Throwable ex = task.getException();
            setStatus("Ошибка: " + (ex.getMessage() != null ? ex.getMessage() : ex), true);
        });
        Thread t = new Thread(task, "launch");
        t.setDaemon(true);
        t.start();
    }

    private void setStatus(String text, boolean error) {
        status.setText(text);
        status.getStyleClass().remove("error");
        if (error) status.getStyleClass().add("error");
    }

    private void setProgress(double f) {
        bar.set(f);
        percent.setText((int) Math.round(f * 100) + "%");
    }

    private Label h1(String t) { Label l = new Label(t); l.getStyleClass().add("h1"); return l; }
    private Label muted(String t) { Label l = new Label(t); l.getStyleClass().add("muted"); l.setWrapText(true); return l; }
    private Label caption(String t) { Label l = new Label(t); l.getStyleClass().add("caption"); return l; }

    private VBox field(String cap, Node n) { return new VBox(8, caption(cap), n); }

    private Button ghost(String text, Runnable r) {
        Button b = new Button(text);
        b.getStyleClass().add("ghost-btn");
        b.setOnAction(e -> r.run());
        return b;
    }

    private void openDir(Path dir) {
        try { Files.createDirectories(dir); } catch (IOException ignored) { }
        getHostServices().showDocument(dir.toUri().toString());
    }

    private void scale(Node n, double to) {
        ScaleTransition st = new ScaleTransition(Duration.millis(140), n);
        st.setToX(to);
        st.setToY(to);
        st.play();
    }

    private void shake(Node n) {
        TranslateTransition tt = new TranslateTransition(Duration.millis(60), n);
        tt.setByX(8);
        tt.setCycleCount(6);
        tt.setAutoReverse(true);
        tt.play();
    }

    private static final class NeonBar extends StackPane {
        private final DoubleProperty value = new SimpleDoubleProperty(0);

        NeonBar() {
            Region track = new Region();
            track.getStyleClass().add("bar-track");
            Region fill = new Region();
            fill.getStyleClass().add("bar-fill");
            fill.setMaxWidth(Region.USE_PREF_SIZE);
            fill.prefWidthProperty().bind(value.multiply(widthProperty()));
            fill.setEffect(new DropShadow(12, Color.web("#8b5cf6", 0.6)));
            setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(track, fill);
            setMinHeight(10);
            setPrefHeight(10);
            setMaxHeight(10);
        }

        void set(double v) {
            new Timeline(new KeyFrame(Duration.millis(350),
                    new KeyValue(value, Math.max(0, Math.min(1, v)), Interpolator.EASE_BOTH))).play();
        }
    }
            }
