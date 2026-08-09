package com.pucilowski.fx;

import spark.Service;

public final class App {

    private final Service http;

    public App(int port) {
        this.http = Service.ignite().port(port);
        this.http.get("/health", (req, res) -> "OK");
        this.http.awaitInitialization();
    }

    public int port() {
        return http.port();
    }

    public void stop() {
        http.stop();
        http.awaitStop();
    }

    public static void main(String[] args) {
        new App(Integer.parseInt(env("PORT", "8081")));
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
