package app.hovanki.client

/**
 * Server the app talks to until the player enters another address on the start screen
 * (a phone on the same Wi-Fi as the development machine, a deployed server).
 */
expect fun defaultServerUrl(): String
