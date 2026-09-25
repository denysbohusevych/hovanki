package app.hovanki.client

/** The iOS simulator shares the network with the host machine. */
actual fun defaultServerUrl(): String = "http://localhost:8080"
