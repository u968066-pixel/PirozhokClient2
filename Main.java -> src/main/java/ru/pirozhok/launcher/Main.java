package ru.pirozhok.launcher;

/** Отдельный main-класс (не наследник Application) — нужен для запуска JavaFX из fat-jar. */
public class Main {
    public static void main(String[] args) {
        App.main(args);
    }
}
